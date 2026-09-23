// pzopt_ngx: NVIDIA DLSS Super Resolution for the pzopt upscaler (docs/plan-upscalers.md, milestone 3).
//
// The game renders with OpenGL; DLSS needs Vulkan (or D3D). This shim owns a Vulkan instance / device on the GPU the
// GL context runs on (matched by device UUID), allocates the four DLSS images (colour, depth, motion vectors, output)
// with exportable memory and two exportable semaphores, and hands their file descriptors to the Java side, which
// imports them into GL (GL_EXT_memory_object_fd / GL_EXT_semaphore_fd). Per frame GL writes the three inputs,
// signals the "GL done" semaphore, this shim runs NGX EvaluateFeature on its queue waiting on it and signals
// "DLSS done", and GL waits on that before it samples the output.
//
// C ABI for the Java FFM downcalls (pzopt.Dlss). Every call returns 0 on success, a negative code on failure with
// the text in pzngx_error(). Render thread only.
//
// Build (Linux): g++ -O2 -shared -fPIC -std=c++17 -I<dlss-sdk>/include -o natives/libpzopt_ngx64.so pzopt_ngx.cpp
//                <dlss-sdk>/lib/Linux_x86_64/libnvsdk_ngx.a -ldl -lpthread
// The DLSS library (libnvidia-ngx-dlss.so.<ver>) is searched in the directory pzngx_init gets.

#ifdef _WIN32
#define VK_USE_PLATFORM_WIN32_KHR
#include <windows.h>
#endif
#include <vulkan/vulkan.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#ifndef _WIN32
#include <dlfcn.h>
#include <unistd.h>
#include <pthread.h>
#else
#include <thread>
#endif
#include <functional>
#include <mutex>
#include <condition_variable>

#include "nvsdk_ngx.h"
#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"

#ifdef _WIN32
#define PZNGX_API extern "C" __declspec(dllexport)
#else
#define PZNGX_API extern "C" __attribute__((visibility("default")))
#endif

#ifdef _WIN32
typedef HANDLE pzngx_handle; // opaque Win32 handles (GL_EXT_memory_object_win32 / GL_EXT_semaphore_win32)
#define PZNGX_MEMORY_HANDLE_TYPE VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
#define PZNGX_SEMAPHORE_HANDLE_TYPE VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT
#else
typedef int pzngx_handle; // opaque fds (GL_EXT_memory_object_fd / GL_EXT_semaphore_fd)
#define PZNGX_MEMORY_HANDLE_TYPE VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT
#define PZNGX_SEMAPHORE_HANDLE_TYPE VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT
#endif

namespace {

const unsigned long long APP_ID = 0x50ff5a0; // any non-zero id for the NGX application record

// NGX's initialisation and feature creation overflow a 1 MB stack (the game's render thread is the JVM's main
// thread: a segfault inside libnvidia-ngx.so at GetFeatureDeviceExtensionRequirements, 2026-09-22; it runs with
// a 4 MB stack). Every entry point below therefore runs on one persistent worker thread with a 64 MB stack, the
// caller waiting for the result, so the Vulkan objects and queue are also used from a single thread.
class BigStackWorker {
public:
   int run(std::function<int()> fn) {
      std::unique_lock<std::mutex> lock(mutex);
      start();
      task = std::move(fn);
      hasTask = true;
      done = false;
      cv.notify_all();
      cv.wait(lock, [this] { return done; });
      return result;
   }
private:
   void start() {
      if (started) return;
      started = true;
#ifdef _WIN32
      CreateThread(nullptr, 64u << 20, &BigStackWorker::entryWin, this, 0, nullptr);
#else
      pthread_attr_t attr;
      pthread_attr_init(&attr);
      pthread_attr_setstacksize(&attr, 64u << 20);
      pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
      pthread_create(&thread, &attr, &BigStackWorker::entry, this);
      pthread_attr_destroy(&attr);
#endif
   }
#ifdef _WIN32
   static DWORD WINAPI entryWin(LPVOID self) {
      static_cast<BigStackWorker*>(self)->loop();
      return 0;
   }
#endif
   static void* entry(void* self) {
      static_cast<BigStackWorker*>(self)->loop();
      return nullptr;
   }
   void loop() {
      std::unique_lock<std::mutex> lock(mutex);
      for (;;) {
         cv.wait(lock, [this] { return hasTask; });
         hasTask = false;
         std::function<int()> fn = std::move(task);
         lock.unlock();
         int r = fn();
         lock.lock();
         result = r;
         done = true;
         cv.notify_all();
      }
   }
   std::mutex mutex;
   std::condition_variable cv;
   std::function<int()> task;
   bool hasTask = false, done = false, started = false;
   int result = 0;
#ifndef _WIN32
   pthread_t thread{};
#endif
};
BigStackWorker& worker = *new BigStackWorker(); // never destroyed: static destruction under a waiting thread hung the game at exit

struct Image {
   VkImage image = VK_NULL_HANDLE;
   VkImageView view = VK_NULL_HANDLE;
   VkDeviceMemory memory = VK_NULL_HANDLE;
   VkDeviceSize bytes = 0;
   VkFormat format = VK_FORMAT_UNDEFINED;
   uint32_t width = 0, height = 0;
   pzngx_handle fd = (pzngx_handle)-1; // exported once, handed to GL (which takes ownership)
   NVSDK_NGX_Resource_VK resource{};
};

struct State {
   std::string error;
   std::string logTail; // NGX log lines (last few)
   void* vulkanLib = nullptr;
   PFN_vkGetInstanceProcAddr gipa = nullptr;
   PFN_vkGetDeviceProcAddr gdpa = nullptr;
   VkInstance instance = VK_NULL_HANDLE;
   VkPhysicalDevice physical = VK_NULL_HANDLE;
   VkDevice device = VK_NULL_HANDLE;
   uint32_t queueFamily = 0;
   VkQueue queue = VK_NULL_HANDLE;
   VkCommandPool pool = VK_NULL_HANDLE;
   static const int RING = 3; // command buffers in flight: the CPU waits only for the one three frames back
   VkCommandBuffer cmds[RING] = {};
   VkFence fences[RING] = {};
   bool fencePending[RING] = {};
   int ring = 0;
   VkCommandBuffer cmd = VK_NULL_HANDLE; // the one being recorded
   VkFence fence = VK_NULL_HANDLE;
   // one or two sets of images + semaphores: two when GL pipelines one frame (it composites the previous frame's
   // output while this frame's evaluation runs, so it never waits for the evaluation it just submitted)
   static const int MAX_SETS = 2;
   int sets = 1;
   VkSemaphore glDone[MAX_SETS] = {};   // GL signals, Vulkan waits
   VkSemaphore dlssDone[MAX_SETS] = {}; // Vulkan signals, GL waits
   pzngx_handle glDoneFd[MAX_SETS] = {(pzngx_handle)-1, (pzngx_handle)-1}, dlssDoneFd[MAX_SETS] = {(pzngx_handle)-1, (pzngx_handle)-1};
   NVSDK_NGX_Parameter* params = nullptr;
   NVSDK_NGX_Handle* feature = nullptr;
   Image images[4 * MAX_SETS]; // per set: 0 colour, 1 depth, 2 motion vectors, 3 output
   uint32_t inW = 0, inH = 0, outW = 0, outH = 0;
   bool ngxInit = false;
   bool created = false;
   bool firstFrame = true;
   long evaluations = 0;
   VkQueryPool timestamps = VK_NULL_HANDLE; // two per ring slot around the evaluation (GPU time of DLSS alone)
   bool slotTimed[RING] = {};
   float timestampPeriod = 1.0f; // ns per tick
   double gpuNs = 0.0; // summed evaluation GPU time since the last pzngx_stats
   long gpuCount = 0;
   long long slotSeq[RING] = {}; // the evaluation number each ring slot was recorded for
   static const int TIMES = 64; // raw start / end (ns on the GPU clock) of the last evaluations, by number
   long long timesSeq[TIMES] = {}, timesStart[TIMES] = {}, timesEnd[TIMES] = {};
   std::wstring dataPath;
   std::wstring dlssDir;
   std::vector<const wchar_t*> pathList;
   NVSDK_NGX_FeatureCommonInfo commonInfo{};
   int deviceIndexOverride = -1;
   VkPhysicalDeviceMemoryProperties memProps{};
} S;

#define VKF(name) PFN_##name name = nullptr
struct Fn {
   VKF(vkCreateInstance); VKF(vkEnumeratePhysicalDevices); VKF(vkGetPhysicalDeviceProperties2);
   VKF(vkGetPhysicalDeviceQueueFamilyProperties); VKF(vkCreateDevice); VKF(vkGetDeviceQueue);
   VKF(vkGetPhysicalDeviceMemoryProperties); VKF(vkEnumerateDeviceExtensionProperties); VKF(vkEnumerateInstanceExtensionProperties);
   VKF(vkDestroyInstance); VKF(vkDestroyDevice); VKF(vkDeviceWaitIdle);
   VKF(vkCreateCommandPool); VKF(vkAllocateCommandBuffers); VKF(vkBeginCommandBuffer); VKF(vkEndCommandBuffer);
   VKF(vkQueueSubmit); VKF(vkCreateFence); VKF(vkWaitForFences); VKF(vkResetFences); VKF(vkDestroyFence);
   VKF(vkDestroyCommandPool); VKF(vkCreateImage); VKF(vkGetImageMemoryRequirements2); VKF(vkAllocateMemory);
   VKF(vkBindImageMemory); VKF(vkCreateImageView); VKF(vkDestroyImageView); VKF(vkDestroyImage); VKF(vkFreeMemory);
#ifdef _WIN32
   VKF(vkGetMemoryWin32HandleKHR); VKF(vkGetSemaphoreWin32HandleKHR);
#else
   VKF(vkGetMemoryFdKHR); VKF(vkGetSemaphoreFdKHR);
#endif
   VKF(vkCreateSemaphore); VKF(vkDestroySemaphore);
   VKF(vkCmdPipelineBarrier); VKF(vkResetCommandBuffer); VKF(vkQueueWaitIdle); VKF(vkGetDeviceProcAddr);
   VKF(vkCreateQueryPool); VKF(vkDestroyQueryPool); VKF(vkCmdResetQueryPool); VKF(vkCmdWriteTimestamp); VKF(vkGetQueryPoolResults);
} F;
#undef VKF

int fail(const std::string& why) {
   S.error = why;
   return -1;
}

int failVk(const char* what, VkResult r) {
   char buf[256];
   snprintf(buf, sizeof buf, "%s: VkResult %d", what, (int)r);
   return fail(buf);
}

int failNgx(const char* what, NVSDK_NGX_Result r) {
   char buf[320];
   snprintf(buf, sizeof buf, "%s: NGX 0x%08x%s%s", what, (unsigned)r, S.logTail.empty() ? "" : " | ", S.logTail.c_str());
   return fail(buf);
}

void ngxLog(const char* message, NVSDK_NGX_Logging_Level, NVSDK_NGX_Feature) {
   if (!message) return;
   std::string m(message);
   while (!m.empty() && (m.back() == '\n' || m.back() == '\r')) m.pop_back();
   S.logTail = m.size() > 200 ? m.substr(m.size() - 200) : m;
   const char* env = getenv("PZOPT_NGX_LOG");
   if (env && *env) fprintf(stderr, "[pzopt ngx] %s\n", m.c_str());
}

std::wstring widen(const char* s) {
   std::wstring w;
   for (; s && *s; ++s) w.push_back((wchar_t)(unsigned char)*s);
   return w;
}

template <class T> T proc(const char* name) {
   T p = (T)S.gipa(S.instance, name);
   if (!p && S.device) p = (T)S.gdpa(S.device, name);
   return p;
}

bool loadVulkan() {
#ifdef _WIN32
   HMODULE lib = LoadLibraryA("vulkan-1.dll");
   if (!lib) return false;
   S.vulkanLib = lib;
   S.gipa = (PFN_vkGetInstanceProcAddr)GetProcAddress(lib, "vkGetInstanceProcAddr");
#else
   const char* names[] = {"libvulkan.so.1", "libvulkan.so"};
   for (const char* n : names) {
      S.vulkanLib = dlopen(n, RTLD_NOW | RTLD_LOCAL);
      if (S.vulkanLib) break;
   }
   if (!S.vulkanLib) return false;
   S.gipa = (PFN_vkGetInstanceProcAddr)dlsym(S.vulkanLib, "vkGetInstanceProcAddr");
#endif
   return S.gipa != nullptr;
}

#define LOAD_I(name) F.name = (PFN_##name)S.gipa(S.instance, #name); if (!F.name) return fail("missing " #name)
#define LOAD_D(name) F.name = (PFN_##name)S.gdpa(S.device, #name); if (!F.name) return fail("missing " #name)

int createInstance() {
   F.vkCreateInstance = (PFN_vkCreateInstance)S.gipa(nullptr, "vkCreateInstance");
   F.vkEnumerateInstanceExtensionProperties = (PFN_vkEnumerateInstanceExtensionProperties)S.gipa(nullptr, "vkEnumerateInstanceExtensionProperties");
   if (!F.vkCreateInstance) return fail("no vkCreateInstance");

   std::vector<const char*> exts = {
      VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME,
      VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME,
      VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME,
   };
   // what NGX wants at instance level
   NVSDK_NGX_FeatureDiscoveryInfo info{};
   info.SDKVersion = NVSDK_NGX_Version_API;
   info.FeatureID = NVSDK_NGX_Feature_SuperSampling;
   info.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Application_Id;
   info.Identifier.v.ApplicationId = APP_ID;
   info.ApplicationDataPath = S.dataPath.c_str();
   info.FeatureInfo = &S.commonInfo;
   uint32_t n = 0;
   VkExtensionProperties* props = nullptr;
   std::vector<std::string> ngxExts;
   if (NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&info, &n, &props) == NVSDK_NGX_Result_Success && props) {
      for (uint32_t i = 0; i < n; i++) ngxExts.push_back(props[i].extensionName);
   }
   for (auto& e : ngxExts) {
      bool have = false;
      for (auto* x : exts) if (e == x) have = true;
      if (!have) exts.push_back(e.c_str());
   }
   VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
   app.pApplicationName = "ProjectZomboid pzopt";
   app.apiVersion = VK_API_VERSION_1_2;
   VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
   ci.pApplicationInfo = &app;
   ci.enabledExtensionCount = (uint32_t)exts.size();
   ci.ppEnabledExtensionNames = exts.data();
   VkResult r = F.vkCreateInstance(&ci, nullptr, &S.instance);
   if (r != VK_SUCCESS) return failVk("vkCreateInstance", r);
   LOAD_I(vkEnumeratePhysicalDevices); LOAD_I(vkGetPhysicalDeviceProperties2); LOAD_I(vkGetPhysicalDeviceQueueFamilyProperties);
   LOAD_I(vkCreateDevice); LOAD_I(vkGetPhysicalDeviceMemoryProperties); LOAD_I(vkEnumerateDeviceExtensionProperties);
   LOAD_I(vkDestroyInstance); LOAD_I(vkGetDeviceProcAddr);
   S.gdpa = F.vkGetDeviceProcAddr;
   return 0;
}

int pickDevice(const uint8_t* glUuid) {
   uint32_t count = 0;
   F.vkEnumeratePhysicalDevices(S.instance, &count, nullptr);
   if (count == 0) return fail("no Vulkan devices");
   std::vector<VkPhysicalDevice> devs(count);
   F.vkEnumeratePhysicalDevices(S.instance, &count, devs.data());
   VkPhysicalDevice byUuid = VK_NULL_HANDLE, nvidia = VK_NULL_HANDLE;
   for (uint32_t i = 0; i < count; i++) {
      VkPhysicalDeviceIDProperties id{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
      VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
      p2.pNext = &id;
      F.vkGetPhysicalDeviceProperties2(devs[i], &p2);
      if (glUuid && memcmp(id.deviceUUID, glUuid, VK_UUID_SIZE) == 0 && byUuid == VK_NULL_HANDLE) byUuid = devs[i];
      if (p2.properties.vendorID == 0x10DE && nvidia == VK_NULL_HANDLE) nvidia = devs[i];
      if (S.deviceIndexOverride == (int)i) byUuid = devs[i];
   }
   S.physical = byUuid ? byUuid : nvidia;
   if (!S.physical) return fail("no NVIDIA Vulkan device (and none matches the GL device UUID)");
   VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
   F.vkGetPhysicalDeviceProperties2(S.physical, &p2);
   if (p2.properties.vendorID != 0x10DE) return fail(std::string("the GL device is not NVIDIA: ") + p2.properties.deviceName);
   F.vkGetPhysicalDeviceMemoryProperties(S.physical, &S.memProps);
   return 0;
}

int createDevice() {
   uint32_t qn = 0;
   F.vkGetPhysicalDeviceQueueFamilyProperties(S.physical, &qn, nullptr);
   std::vector<VkQueueFamilyProperties> qs(qn);
   F.vkGetPhysicalDeviceQueueFamilyProperties(S.physical, &qn, qs.data());
   bool found = false;
   for (uint32_t i = 0; i < qn; i++) {
      if ((qs[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && (qs[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) { S.queueFamily = i; found = true; break; }
   }
   if (!found) return fail("no graphics+compute queue family");

#ifdef _WIN32
   const char* memExt = VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
   const char* semExt = VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;
#else
   const char* memExt = VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
   const char* semExt = VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
#endif
   std::vector<std::string> want = {
      VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME, memExt,
      VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME, semExt,
      VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME, VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
   };
   NVSDK_NGX_FeatureDiscoveryInfo info{};
   info.SDKVersion = NVSDK_NGX_Version_API;
   info.FeatureID = NVSDK_NGX_Feature_SuperSampling;
   info.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Application_Id;
   info.Identifier.v.ApplicationId = APP_ID;
   info.ApplicationDataPath = S.dataPath.c_str();
   info.FeatureInfo = &S.commonInfo;
   uint32_t n = 0;
   VkExtensionProperties* props = nullptr;
   if (NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(S.instance, S.physical, &info, &n, &props) == NVSDK_NGX_Result_Success && props) {
      for (uint32_t i = 0; i < n; i++) {
         std::string e = props[i].extensionName;
         bool have = false;
         for (auto& w : want) if (w == e) have = true;
         if (!have) want.push_back(e);
      }
   }
   // keep only what the device offers (NGX lists optional ones too)
   uint32_t en = 0;
   F.vkEnumerateDeviceExtensionProperties(S.physical, nullptr, &en, nullptr);
   std::vector<VkExtensionProperties> avail(en);
   F.vkEnumerateDeviceExtensionProperties(S.physical, nullptr, &en, avail.data());
   std::vector<const char*> exts;
   for (auto& w : want) {
      bool ok = false;
      for (auto& a : avail) if (w == a.extensionName) ok = true;
      if (ok) exts.push_back(w.c_str());
      else if (w == memExt || w == semExt)
         return fail("device lacks " + w);
   }
   float prio = 1.0f;
   VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
   qi.queueFamilyIndex = S.queueFamily;
   qi.queueCount = 1;
   qi.pQueuePriorities = &prio;
   VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
   f12.shaderFloat16 = VK_TRUE; // DLSS likes fp16; ignored when unsupported (not the case on RTX)
   f12.storageBuffer8BitAccess = VK_TRUE;
   f12.uniformAndStorageBuffer8BitAccess = VK_TRUE;
   VkPhysicalDeviceVulkan11Features f11{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES};
   f11.pNext = &f12;
   f11.storageBuffer16BitAccess = VK_TRUE;
   f11.uniformAndStorageBuffer16BitAccess = VK_TRUE;
   VkPhysicalDeviceFeatures2 feats{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
   feats.pNext = &f11;
   feats.features.shaderInt16 = VK_TRUE;
   feats.features.shaderStorageImageWriteWithoutFormat = VK_TRUE;
   feats.features.shaderStorageImageReadWithoutFormat = VK_TRUE;
   VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
   di.pNext = &feats;
   di.queueCreateInfoCount = 1;
   di.pQueueCreateInfos = &qi;
   di.enabledExtensionCount = (uint32_t)exts.size();
   di.ppEnabledExtensionNames = exts.data();
   VkResult r = F.vkCreateDevice(S.physical, &di, nullptr, &S.device);
   if (r != VK_SUCCESS) {
      // retry without the optional features
      di.pNext = nullptr;
      r = F.vkCreateDevice(S.physical, &di, nullptr, &S.device);
      if (r != VK_SUCCESS) return failVk("vkCreateDevice", r);
   }
   LOAD_D(vkGetDeviceQueue); LOAD_D(vkDestroyDevice); LOAD_D(vkDeviceWaitIdle); LOAD_D(vkCreateCommandPool);
   LOAD_D(vkAllocateCommandBuffers); LOAD_D(vkBeginCommandBuffer); LOAD_D(vkEndCommandBuffer); LOAD_D(vkQueueSubmit);
   LOAD_D(vkCreateFence); LOAD_D(vkWaitForFences); LOAD_D(vkResetFences); LOAD_D(vkDestroyFence); LOAD_D(vkDestroyCommandPool);
   LOAD_D(vkCreateImage); LOAD_D(vkGetImageMemoryRequirements2); LOAD_D(vkAllocateMemory); LOAD_D(vkBindImageMemory);
   LOAD_D(vkCreateImageView); LOAD_D(vkDestroyImageView); LOAD_D(vkDestroyImage); LOAD_D(vkFreeMemory);
#ifdef _WIN32
   LOAD_D(vkGetMemoryWin32HandleKHR); LOAD_D(vkGetSemaphoreWin32HandleKHR);
#else
   LOAD_D(vkGetMemoryFdKHR); LOAD_D(vkGetSemaphoreFdKHR);
#endif
   LOAD_D(vkCreateSemaphore); LOAD_D(vkDestroySemaphore);
   LOAD_D(vkCmdPipelineBarrier); LOAD_D(vkResetCommandBuffer); LOAD_D(vkQueueWaitIdle);
   LOAD_D(vkCreateQueryPool); LOAD_D(vkDestroyQueryPool); LOAD_D(vkCmdResetQueryPool); LOAD_D(vkCmdWriteTimestamp); LOAD_D(vkGetQueryPoolResults);
   F.vkGetDeviceQueue(S.device, S.queueFamily, 0, &S.queue);

   VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
   pi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
   pi.queueFamilyIndex = S.queueFamily;
   r = F.vkCreateCommandPool(S.device, &pi, nullptr, &S.pool);
   if (r != VK_SUCCESS) return failVk("vkCreateCommandPool", r);
   VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
   ai.commandPool = S.pool;
   ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
   ai.commandBufferCount = State::RING;
   r = F.vkAllocateCommandBuffers(S.device, &ai, S.cmds);
   if (r != VK_SUCCESS) return failVk("vkAllocateCommandBuffers", r);
   VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
   for (int i = 0; i < State::RING; i++) {
      r = F.vkCreateFence(S.device, &fi, nullptr, &S.fences[i]);
      if (r != VK_SUCCESS) return failVk("vkCreateFence", r);
      S.fencePending[i] = false;
   }
   S.cmd = S.cmds[0];
   S.fence = S.fences[0];
   // timestamps (optional: a queue family without timestamp bits just reports no GPU time)
   if (qs[S.queueFamily].timestampValidBits > 0) {
      VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
      F.vkGetPhysicalDeviceProperties2(S.physical, &p2);
      S.timestampPeriod = p2.properties.limits.timestampPeriod;
      VkQueryPoolCreateInfo qp{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
      qp.queryType = VK_QUERY_TYPE_TIMESTAMP;
      qp.queryCount = 2 * State::RING;
      if (F.vkCreateQueryPool(S.device, &qp, nullptr, &S.timestamps) != VK_SUCCESS) S.timestamps = VK_NULL_HANDLE;
   }
   return 0;
}

// the evaluation GPU time of a ring slot whose fence has signalled
void collectTimestamps(int slot) {
   if (!S.timestamps || !S.slotTimed[slot]) return;
   uint64_t t[2] = {0, 0};
   if (F.vkGetQueryPoolResults(S.device, S.timestamps, 2 * slot, 2, sizeof t, t, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS && t[1] > t[0]) {
      S.gpuNs += (double)(t[1] - t[0]) * S.timestampPeriod;
      S.gpuCount++;
      int k = (int)(S.slotSeq[slot] % State::TIMES);
      S.timesSeq[k] = S.slotSeq[slot];
      S.timesStart[k] = (long long)((double)t[0] * S.timestampPeriod);
      S.timesEnd[k] = (long long)((double)t[1] * S.timestampPeriod);
   }
   S.slotTimed[slot] = false;
}

uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags flags) {
   for (uint32_t i = 0; i < S.memProps.memoryTypeCount; i++) {
      if ((bits & (1u << i)) && (S.memProps.memoryTypes[i].propertyFlags & flags) == flags) return i;
   }
   return UINT32_MAX;
}

int createImage(Image& img, uint32_t w, uint32_t h, VkFormat format, VkImageUsageFlags usage, bool readWrite) {
   img.width = w; img.height = h; img.format = format;
   VkExternalMemoryImageCreateInfo ext{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
   ext.handleTypes = PZNGX_MEMORY_HANDLE_TYPE;
   VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
   ci.pNext = &ext;
   ci.imageType = VK_IMAGE_TYPE_2D;
   ci.format = format;
   ci.extent = {w, h, 1};
   ci.mipLevels = 1;
   ci.arrayLayers = 1;
   ci.samples = VK_SAMPLE_COUNT_1_BIT;
   ci.tiling = VK_IMAGE_TILING_OPTIMAL;
   ci.usage = usage;
   ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
   ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
   VkResult r = F.vkCreateImage(S.device, &ci, nullptr, &img.image);
   if (r != VK_SUCCESS) return failVk("vkCreateImage", r);

   VkMemoryDedicatedRequirements ded{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_REQUIREMENTS};
   VkMemoryRequirements2 req{VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2};
   req.pNext = &ded;
   VkImageMemoryRequirementsInfo2 ri{VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2};
   ri.image = img.image;
   F.vkGetImageMemoryRequirements2(S.device, &ri, &req);
   img.bytes = req.memoryRequirements.size;

   VkMemoryDedicatedAllocateInfo dai{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
   dai.image = img.image;
   VkExportMemoryAllocateInfo exp{VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO};
   exp.pNext = &dai; // always dedicated: what GL_EXT_memory_object expects for an imported image allocation
   exp.handleTypes = PZNGX_MEMORY_HANDLE_TYPE;
   VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
   mai.pNext = &exp;
   mai.allocationSize = req.memoryRequirements.size;
   mai.memoryTypeIndex = memoryType(req.memoryRequirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
   if (mai.memoryTypeIndex == UINT32_MAX) return fail("no device-local memory type for the image");
   r = F.vkAllocateMemory(S.device, &mai, nullptr, &img.memory);
   if (r != VK_SUCCESS) return failVk("vkAllocateMemory", r);
   r = F.vkBindImageMemory(S.device, img.image, img.memory, 0);
   if (r != VK_SUCCESS) return failVk("vkBindImageMemory", r);

   VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
   vi.image = img.image;
   vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
   vi.format = format;
   vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
   r = F.vkCreateImageView(S.device, &vi, nullptr, &img.view);
   if (r != VK_SUCCESS) return failVk("vkCreateImageView", r);

#ifdef _WIN32
   VkMemoryGetWin32HandleInfoKHR gi{VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR};
   gi.memory = img.memory;
   gi.handleType = PZNGX_MEMORY_HANDLE_TYPE;
   r = F.vkGetMemoryWin32HandleKHR(S.device, &gi, &img.fd);
   if (r != VK_SUCCESS) return failVk("vkGetMemoryWin32HandleKHR", r);
#else
   VkMemoryGetFdInfoKHR gi{VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR};
   gi.memory = img.memory;
   gi.handleType = PZNGX_MEMORY_HANDLE_TYPE;
   r = F.vkGetMemoryFdKHR(S.device, &gi, &img.fd);
   if (r != VK_SUCCESS) return failVk("vkGetMemoryFdKHR", r);
#endif

   img.resource = NVSDK_NGX_Create_ImageView_Resource_VK(img.view, img.image, vi.subresourceRange, format, w, h, readWrite);
   return 0;
}

void destroyImage(Image& img) {
   if (img.view) F.vkDestroyImageView(S.device, img.view, nullptr);
   if (img.image) F.vkDestroyImage(S.device, img.image, nullptr);
   if (img.memory) F.vkFreeMemory(S.device, img.memory, nullptr);
   img = Image{};
}

int createSemaphore(VkSemaphore& sem, pzngx_handle& fd) {
   VkExportSemaphoreCreateInfo exp{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
   exp.handleTypes = PZNGX_SEMAPHORE_HANDLE_TYPE;
   VkSemaphoreCreateInfo ci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
   ci.pNext = &exp;
   VkResult r = F.vkCreateSemaphore(S.device, &ci, nullptr, &sem);
   if (r != VK_SUCCESS) return failVk("vkCreateSemaphore", r);
#ifdef _WIN32
   VkSemaphoreGetWin32HandleInfoKHR gi{VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR};
   gi.semaphore = sem;
   gi.handleType = PZNGX_SEMAPHORE_HANDLE_TYPE;
   r = F.vkGetSemaphoreWin32HandleKHR(S.device, &gi, &fd);
   if (r != VK_SUCCESS) return failVk("vkGetSemaphoreWin32HandleKHR", r);
#else
   VkSemaphoreGetFdInfoKHR gi{VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR};
   gi.semaphore = sem;
   gi.handleType = PZNGX_SEMAPHORE_HANDLE_TYPE;
   r = F.vkGetSemaphoreFdKHR(S.device, &gi, &fd);
   if (r != VK_SUCCESS) return failVk("vkGetSemaphoreFdKHR", r);
#endif
   return 0;
}

void barrier(VkImage image, VkImageLayout from, VkImageLayout to, VkAccessFlags srcAccess, VkAccessFlags dstAccess) {
   VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
   b.oldLayout = from;
   b.newLayout = to;
   b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
   b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
   b.image = image;
   b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
   b.srcAccessMask = srcAccess;
   b.dstAccessMask = dstAccess;
   F.vkCmdPipelineBarrier(S.cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
}

NVSDK_NGX_PerfQuality_Value quality(int q) {
   switch (q) {
      case 0: return NVSDK_NGX_PerfQuality_Value_MaxQuality;
      case 1: return NVSDK_NGX_PerfQuality_Value_Balanced;
      case 2: return NVSDK_NGX_PerfQuality_Value_MaxPerf;
      case 3: return NVSDK_NGX_PerfQuality_Value_UltraPerformance;
      case 4: return NVSDK_NGX_PerfQuality_Value_DLAA;
      default: return NVSDK_NGX_PerfQuality_Value_MaxQuality;
   }
}

void releaseFeature() {
   if (S.feature) {
      F.vkDeviceWaitIdle(S.device);
      NVSDK_NGX_VULKAN_ReleaseFeature(S.feature);
      S.feature = nullptr;
   }
   for (auto& img : S.images) destroyImage(img);
   for (int k = 0; k < State::MAX_SETS; k++) {
      if (S.glDone[k]) { F.vkDestroySemaphore(S.device, S.glDone[k], nullptr); S.glDone[k] = VK_NULL_HANDLE; }
      if (S.dlssDone[k]) { F.vkDestroySemaphore(S.device, S.dlssDone[k], nullptr); S.dlssDone[k] = VK_NULL_HANDLE; }
   }
   S.created = false;
}

} // namespace

PZNGX_API const char* pzngx_error(void) {
   return S.error.c_str();
}

PZNGX_API const char* pzngx_version(void) {
   static char buf[64];
   snprintf(buf, sizeof buf, "ngx sdk 0x%08x", (unsigned)NVSDK_NGX_Version_API);
   return buf;
}

/**
 * Loads Vulkan, creates the instance and device on the GPU whose UUID glDeviceUuid (16 bytes, may be NULL) names,
 * initialises NGX with dlssDir as the extra search path of the DLSS library and appDataPath for its logs / caches.
 */
static int pzngx_init_impl(const char* appDataPath, const char* dlssDir, const uint8_t* glDeviceUuid, int deviceIndexOverride) {
   if (S.ngxInit) return 0;
   S.error.clear();
   S.deviceIndexOverride = deviceIndexOverride;
   if (!loadVulkan()) return fail("libvulkan.so.1 not found");
   S.dataPath = widen(appDataPath ? appDataPath : ".");
   S.dlssDir = widen(dlssDir ? dlssDir : ".");
   S.pathList = {S.dlssDir.c_str()};
   S.commonInfo = NVSDK_NGX_FeatureCommonInfo{};
   S.commonInfo.PathListInfo.Path = S.pathList.data();
   S.commonInfo.PathListInfo.Length = (unsigned)S.pathList.size();
   S.commonInfo.LoggingInfo.LoggingCallback = ngxLog;
   S.commonInfo.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_ON;
   S.commonInfo.LoggingInfo.DisableOtherLoggingSinks = true;

   int rc = createInstance();
   if (rc) return rc;
   rc = pickDevice(glDeviceUuid);
   if (rc) return rc;
   rc = createDevice();
   if (rc) return rc;

   NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_Init(APP_ID, S.dataPath.c_str(), S.instance, S.physical, S.device, S.gipa, S.gdpa, &S.commonInfo, NVSDK_NGX_Version_API);
   if (NVSDK_NGX_FAILED(r)) return failNgx("NVSDK_NGX_VULKAN_Init", r);
   S.ngxInit = true;

   r = NVSDK_NGX_VULKAN_GetCapabilityParameters(&S.params);
   if (NVSDK_NGX_FAILED(r) || !S.params) return failNgx("GetCapabilityParameters", r);
   int needsDriver = 0, available = 0;
   NVSDK_NGX_Parameter_GetI(S.params, NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &needsDriver);
   NVSDK_NGX_Parameter_GetI(S.params, NVSDK_NGX_Parameter_SuperSampling_Available, &available);
   if (needsDriver) {
      unsigned major = 0, minor = 0;
      NVSDK_NGX_Parameter_GetUI(S.params, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMajor, &major);
      NVSDK_NGX_Parameter_GetUI(S.params, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMinor, &minor);
      char buf[128];
      snprintf(buf, sizeof buf, "DLSS needs driver %u.%u or newer", major, minor);
      return fail(buf);
   }
   if (!available) {
      int initResult = 0;
      NVSDK_NGX_Parameter_GetI(S.params, NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, &initResult);
      char buf[200];
      snprintf(buf, sizeof buf, "DLSS not available on this GPU / driver (feature init result 0x%08x)%s%s", (unsigned)initResult, S.logTail.empty() ? "" : " | ", S.logTail.c_str());
      return fail(buf);
   }
   return 0;
}

PZNGX_API int pzngx_init(const char* appDataPath, const char* dlssDir, const uint8_t* glDeviceUuid, int deviceIndexOverride) {
   return worker.run([=] { return pzngx_init_impl(appDataPath, dlssDir, glDeviceUuid, deviceIndexOverride); });
}

/** DLSS's optimal render size for the output size and quality (0 quality, 1 balanced, 2 performance, 3 ultra, 4 DLAA). */
static int pzngx_optimal_impl(int q, int outW, int outH, int* inW, int* inH, float* sharpness) {
   if (!S.params) return fail("not initialised");
   unsigned ow = 0, oh = 0, maxW = 0, maxH = 0, minW = 0, minH = 0;
   float sh = 0.0f;
   NVSDK_NGX_Result r = NGX_DLSS_GET_OPTIMAL_SETTINGS(S.params, (unsigned)outW, (unsigned)outH, quality(q), &ow, &oh, &maxW, &maxH, &minW, &minH, &sh);
   if (NVSDK_NGX_FAILED(r)) return failNgx("GetOptimalSettings", r);
   if (ow == 0 || oh == 0) return fail("DLSS reports no render size for this quality");
   *inW = (int)ow; *inH = (int)oh; *sharpness = sh;
   return 0;
}

/**
 * Creates the shared images (inW x inH colour RGBA8, depth R32F, motion vectors RG16F; outW x outH output RGBA8),
 * the two semaphores and the DLSS feature. flags: bit 0 depth inverted (larger = nearer), bit 1 sharpening,
 * bit 2 auto exposure off (an exposure of 1 is assumed), bit 3 HDR, bits 8..15 the render preset, bit 16 two image sets.
 */
PZNGX_API int pzngx_optimal(int q, int outW, int outH, int* inW, int* inH, float* sharpness) {
   return worker.run([=] { return pzngx_optimal_impl(q, outW, outH, inW, inH, sharpness); });
}

static int pzngx_create_impl(int inW, int inH, int outW, int outH, int q, int flags) {
   if (!S.ngxInit) return fail("not initialised");
   // the render preset (flags bits 8..15: 0 = the driver's default, else the NVSDK_NGX_DLSS_Hint_Render_Preset value
   // (E / F the older convolutional models, J / K / L / M the transformer ones) for every quality level
   unsigned preset = (unsigned)((flags >> 8) & 0xFF);
   const char* presetKeys[] = {NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality,
      NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance,
      NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraQuality};
   for (const char* k : presetKeys) NVSDK_NGX_Parameter_SetUI(S.params, k, preset);
   S.error.clear();
   if (S.created) releaseFeature();
   S.inW = inW; S.inH = inH; S.outW = outW; S.outH = outH;
   S.sets = (flags & (1 << 16)) ? 2 : 1;
   int rc = 0;
   for (int k = 0; k < S.sets; k++) {
   Image* im = &S.images[4 * k];
   rc = createImage(im[0], inW, inH, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, false);
   if (rc) return rc;
   rc = createImage(im[1], inW, inH, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, false);
   if (rc) return rc;
   rc = createImage(im[2], inW, inH, VK_FORMAT_R16G16_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, false);
   if (rc) return rc;
   rc = createImage(im[3], outW, outH, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, true);
   if (rc) return rc;
   rc = createSemaphore(S.glDone[k], S.glDoneFd[k]);
   if (rc) return rc;
   rc = createSemaphore(S.dlssDone[k], S.dlssDoneFd[k]);
   if (rc) return rc;
   }

   // the feature: its creation records work into the command buffer
   NVSDK_NGX_DLSS_Create_Params cp{};
   cp.Feature.InWidth = inW;
   cp.Feature.InHeight = inH;
   cp.Feature.InTargetWidth = outW;
   cp.Feature.InTargetHeight = outH;
   cp.Feature.InPerfQualityValue = quality(q);
   int f = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes;
   if (flags & 1) f |= NVSDK_NGX_DLSS_Feature_Flags_DepthInverted;
   if (flags & 2) f |= (1 << 5); // NVSDK_NGX_DLSS_Feature_Flags_DoSharpening (deprecated in the SDK headers; the driver may ignore it)
   if (!(flags & 4)) f |= NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
   if (flags & 8) f |= NVSDK_NGX_DLSS_Feature_Flags_IsHDR;
   cp.InFeatureCreateFlags = f;
   cp.InEnableOutputSubrects = false;

   // everything in flight must be done before the images and the feature change
   for (int i = 0; i < State::RING; i++) {
      if (S.fencePending[i]) { F.vkWaitForFences(S.device, 1, &S.fences[i], VK_TRUE, UINT64_MAX); S.fencePending[i] = false; }
   }
   S.ring = 0;
   S.cmd = S.cmds[0];
   S.fence = S.fences[0];
   VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
   bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
   F.vkResetCommandBuffer(S.cmd, 0);
   F.vkBeginCommandBuffer(S.cmd, &bi);
   // the images start UNDEFINED; move them to the layouts GL will be told about at the first signal
   for (int k = 0; k < S.sets; k++) {
      Image* im = &S.images[4 * k];
      barrier(im[0].image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, VK_ACCESS_SHADER_READ_BIT);
      barrier(im[1].image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, VK_ACCESS_SHADER_READ_BIT);
      barrier(im[2].image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, VK_ACCESS_SHADER_READ_BIT);
      barrier(im[3].image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
   }
   NVSDK_NGX_Result r = NGX_VULKAN_CREATE_DLSS_EXT1(S.device, S.cmd, 1, 1, &S.feature, S.params, &cp);
   F.vkEndCommandBuffer(S.cmd);
   if (NVSDK_NGX_FAILED(r)) return failNgx("CreateFeature", r);
   VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
   si.commandBufferCount = 1;
   si.pCommandBuffers = &S.cmd;
   F.vkResetFences(S.device, 1, &S.fence);
   VkResult vr = F.vkQueueSubmit(S.queue, 1, &si, S.fence);
   if (vr != VK_SUCCESS) return failVk("vkQueueSubmit (create)", vr);
   F.vkWaitForFences(S.device, 1, &S.fence, VK_TRUE, UINT64_MAX);
   S.created = true;
   S.firstFrame = true;
   S.evaluations = 0;
   return 0;
}

PZNGX_API int pzngx_create(int inW, int inH, int outW, int outH, int q, int flags) {
   return worker.run([=] { return pzngx_create_impl(inW, inH, outW, outH, q, flags); });
}

/** The exported memory handle of an image (0 colour, 1 depth, 2 motion vectors, 3 output): an fd on Linux, a Win32 HANDLE on Windows; GL's import consumes it. */
PZNGX_API long long pzngx_image_fd(int which) {
   if (which < 0 || which >= 4 * S.sets || !S.created) return -1;
   pzngx_handle fd = S.images[which].fd;
   S.images[which].fd = (pzngx_handle)-1;
   return (long long)(intptr_t)fd;
}

PZNGX_API long long pzngx_image_bytes(int which) {
   if (which < 0 || which >= 4 * S.sets || !S.created) return 0;
   return (long long)S.images[which].bytes;
}

/** 2k = set k's "GL done" semaphore GL signals, 2k + 1 = its "DLSS done" semaphore GL waits on; GL's import consumes the handle. */
PZNGX_API long long pzngx_semaphore_fd(int which) {
   int k = which / 2;
   if (!S.created || which < 0 || k >= S.sets) return -1;
   pzngx_handle& h = (which & 1) == 0 ? S.glDoneFd[k] : S.dlssDoneFd[k];
   pzngx_handle fd = h;
   h = (pzngx_handle)-1;
   return (long long)(intptr_t)fd;
}

/**
 * One frame: waits for GL's signal on the queue, runs DLSS, signals for GL. jitter in input pixels, motion vectors
 * scaled by mvScale (1 = already in input pixels, from the current to the previous position), reset = 1 on a cut,
 * sharpness 0..1 (only with the sharpening flag), frameMs the frame time.
 */
static int pzngx_evaluate_impl(float jitterX, float jitterY, float mvScaleX, float mvScaleY, int reset, float sharpness, float frameMs, int set) {
   if (!S.created) return fail("no feature");
   if (set < 0 || set >= S.sets) return fail("no such image set");
   Image* im = &S.images[4 * set];
   S.error.clear();
   // the command buffer three frames back must be done before it is re-recorded (the GPU keeps up to three
   // evaluations queued behind the GL work; the CPU never waits for the one it just submitted)
   S.ring = (S.ring + 1) % State::RING;
   S.cmd = S.cmds[S.ring];
   S.fence = S.fences[S.ring];
   if (S.fencePending[S.ring]) {
      F.vkWaitForFences(S.device, 1, &S.fence, VK_TRUE, UINT64_MAX);
      S.fencePending[S.ring] = false;
   }
   collectTimestamps(S.ring);
   F.vkResetFences(S.device, 1, &S.fence);
   F.vkResetCommandBuffer(S.cmd, 0);
   VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
   bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
   F.vkBeginCommandBuffer(S.cmd, &bi);
   if (S.timestamps) {
      F.vkCmdResetQueryPool(S.cmd, S.timestamps, 2 * S.ring, 2);
      F.vkCmdWriteTimestamp(S.cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, S.timestamps, 2 * S.ring);
   }
   // GL left the inputs in SHADER_READ_ONLY and the output in GENERAL (the layouts of its signal); make the writes visible
   for (int i = 0; i < 3; i++) barrier(im[i].image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
   barrier(im[3].image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_MEMORY_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);

   NVSDK_NGX_VK_DLSS_Eval_Params ep{};
   ep.Feature.pInColor = &im[0].resource;
   ep.Feature.pInOutput = &im[3].resource;
   ep.Feature.InSharpness = sharpness;
   ep.pInDepth = &im[1].resource;
   ep.pInMotionVectors = &im[2].resource;
   ep.InJitterOffsetX = jitterX;
   ep.InJitterOffsetY = jitterY;
   ep.InRenderSubrectDimensions = {S.inW, S.inH};
   ep.InReset = reset || S.firstFrame ? 1 : 0;
   ep.InMVScaleX = mvScaleX;
   ep.InMVScaleY = mvScaleY;
   ep.InFrameTimeDeltaInMsec = frameMs;
   NVSDK_NGX_Result r = NGX_VULKAN_EVALUATE_DLSS_EXT(S.cmd, S.feature, S.params, &ep);
   // back to the layouts GL expects at its wait
   barrier(im[3].image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT);
   if (S.timestamps) {
      F.vkCmdWriteTimestamp(S.cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, S.timestamps, 2 * S.ring + 1);
      S.slotTimed[S.ring] = true;
      S.slotSeq[S.ring] = S.evaluations + 1; // the number this evaluation gets below
   }
   F.vkEndCommandBuffer(S.cmd);
   if (NVSDK_NGX_FAILED(r)) return failNgx("EvaluateFeature", r);

   VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
   VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
   si.waitSemaphoreCount = 1;
   si.pWaitSemaphores = &S.glDone[set];
   si.pWaitDstStageMask = &waitStage;
   si.commandBufferCount = 1;
   si.pCommandBuffers = &S.cmd;
   si.signalSemaphoreCount = 1;
   si.pSignalSemaphores = &S.dlssDone[set];
   VkResult vr = F.vkQueueSubmit(S.queue, 1, &si, S.fence);
   if (vr != VK_SUCCESS) return failVk("vkQueueSubmit (evaluate)", vr);
   S.fencePending[S.ring] = true;
   S.firstFrame = false;
   S.evaluations++;
   return 0;
}

PZNGX_API int pzngx_evaluate(float jitterX, float jitterY, float mvScaleX, float mvScaleY, int reset, float sharpness, float frameMs) {
   return worker.run([=] { return pzngx_evaluate_impl(jitterX, jitterY, mvScaleX, mvScaleY, reset, sharpness, frameMs, 0); });
}

/** pzngx_evaluate on image set `set` (0 or 1, see the create flag bit 16). */
PZNGX_API int pzngx_evaluate_set(float jitterX, float jitterY, float mvScaleX, float mvScaleY, int reset, float sharpness, float frameMs, int set) {
   return worker.run([=] { return pzngx_evaluate_impl(jitterX, jitterY, mvScaleX, mvScaleY, reset, sharpness, frameMs, set); });
}

PZNGX_API long long pzngx_evaluations(void) {
   return S.evaluations;
}

/** The GPU-clock start / end (ns) of evaluation number `seq` (1-based) once collected; 0 when not (yet) known. */
PZNGX_API int pzngx_times(long long seq, long long* startNs, long long* endNs) {
   return worker.run([=] {
      int k = (int)(seq % State::TIMES);
      if (S.timesSeq[k] != seq) return -1;
      *startNs = S.timesStart[k];
      *endNs = S.timesEnd[k];
      return 0;
   });
}

/** The mean GPU time of the evaluations collected since the last call, in microseconds (-1 when none were timed). */
PZNGX_API double pzngx_gpu_us(void) {
   double us = -1.0;
   worker.run([&us] { // the counters belong to the worker thread
      if (S.gpuCount > 0) us = S.gpuNs / S.gpuCount / 1000.0;
      S.gpuNs = 0.0;
      S.gpuCount = 0;
      return 0;
   });
   return us;
}

PZNGX_API void pzngx_destroy(void) {
   if (S.created) worker.run([] { releaseFeature(); return 0; });
}

static int pzngx_shutdown_impl() {
   if (S.created) releaseFeature();
   if (S.params) { NVSDK_NGX_VULKAN_DestroyParameters(S.params); S.params = nullptr; }
   if (S.ngxInit && S.device) { NVSDK_NGX_VULKAN_Shutdown1(S.device); S.ngxInit = false; }
   if (S.device) {
      if (S.timestamps) { F.vkDestroyQueryPool(S.device, S.timestamps, nullptr); S.timestamps = VK_NULL_HANDLE; }
      for (int i = 0; i < State::RING; i++) if (S.fences[i]) F.vkDestroyFence(S.device, S.fences[i], nullptr);
      if (S.pool) F.vkDestroyCommandPool(S.device, S.pool, nullptr);
      F.vkDestroyDevice(S.device, nullptr);
      S.device = VK_NULL_HANDLE;
   }
   if (S.instance) { F.vkDestroyInstance(S.instance, nullptr); S.instance = VK_NULL_HANDLE; }
   return 0;
}

PZNGX_API void pzngx_shutdown(void) {
   worker.run([] { return pzngx_shutdown_impl(); });
}
