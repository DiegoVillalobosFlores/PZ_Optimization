// Compiles and links GLSL fragment units with the real driver (headless EGL, desktop GL compatibility context),
// printing the info logs: checks pzopt's shader injections against the game's shaders without launching the game.
//   cc -O2 -o /tmp/glslcheck tools/hdr/glslcheck.c -lEGL -lGL && /tmp/glslcheck vert.glsl frag1.glsl [frag2.glsl...]
#include <EGL/egl.h>
#include <EGL/eglext.h>
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>
#include <stdio.h>
#include <stdlib.h>

static char *slurp(const char *p) {
   FILE *f = fopen(p, "rb");
   if (!f) { perror(p); exit(2); }
   fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
   char *b = malloc(n + 1); fread(b, 1, n, f); b[n] = 0; fclose(f);
   return b;
}

static GLuint unit(GLenum type, const char *path) {
   const char *src = slurp(path);
   GLuint s = glCreateShader(type);
   glShaderSource(s, 1, &src, NULL);
   glCompileShader(s);
   GLint ok; char log[8192];
   glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
   glGetShaderInfoLog(s, sizeof log, NULL, log);
   printf("%s: compile %s\n%s", path, ok ? "ok" : "FAILED", log);
   return s;
}

int main(int argc, char **argv) {
   EGLDisplay d = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
   if (d == EGL_NO_DISPLAY) d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
   eglInitialize(d, NULL, NULL);
   eglBindAPI(EGL_OPENGL_API);
   EGLint ca[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
   EGLConfig cfg; EGLint n;
   eglChooseConfig(d, ca, &cfg, 1, &n);
   EGLint cx[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
                  EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT, EGL_NONE};
   EGLContext c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, cx);
   EGLint pa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
   EGLSurface s = eglCreatePbufferSurface(d, cfg, pa);
   if (!eglMakeCurrent(d, s, s, c)) { fprintf(stderr, "no context\n"); return 1; }
   printf("GL %s\n", glGetString(GL_RENDERER));
   GLuint p = glCreateProgram();
   glAttachShader(p, unit(GL_VERTEX_SHADER, argv[1]));
   for (int i = 2; i < argc; i++) glAttachShader(p, unit(GL_FRAGMENT_SHADER, argv[i]));
   glLinkProgram(p);
   GLint ok; char log[8192];
   glGetProgramiv(p, GL_LINK_STATUS, &ok);
   glGetProgramInfoLog(p, sizeof log, NULL, log);
   printf("link %s\n%s", ok ? "ok" : "FAILED", log);
   if (!ok) return 1;
   // the game's ShaderProgram.compile also validates, with every sampler still on unit 0: two sampler types on one unit
   // fail there on Mesa (pzopt.PixelLight, 2026-09-25)
   glValidateProgram(p);
   glGetProgramiv(p, GL_VALIDATE_STATUS, &ok);
   glGetProgramInfoLog(p, sizeof log, NULL, log);
   printf("validate %s\n%s", ok ? "ok" : "FAILED", log);
   return ok ? 0 : 1;
}
