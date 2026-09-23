-- pzopt: "Update PZ Optimization" item in the main menu.
--  The Java side (pzopt.Updater, reached through the overridden PerformanceSettings) asks the GitHub
--  releases once per boot whether a newer build for this game revision exists. The main menu (never
--  the pause menu) has one more item in the style of the stock ones (ISLabel, UIFont.Large, the same
--  hover fade and sounds) between Credits and Exit: greyed out and inert while the check runs and when
--  the build is current, enabled while a newer build is offered. A small line under it names the installed
--  build ("Version <commit>", "Version <commit> -> <offered commit>" while an update is offered). Clicking it opens a small dialog: what is installed, what is available, the release notes, and "Update now": the download
--  and the file swap run on a daemon thread while the item shows the progress; once done the dialog
--  offers to quit, because the classes the JVM already loaded stay the old ones until a restart.
--  A copy without pzopt-installed.txt (a hand-unpacked zip) cannot swap its own files: the dialog
--  then only opens the release page.
--  Controller: the item has its own row in the menu's joypad list while it is enabled (the D-pad reaches
--  it between Credits and Exit, A is the click), the dialog takes the joypad focus like the stock modals
--  (A = first button, B = second one or close, D-pad scrolls the notes) and hands it back to the item.
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/client/pzopt/ (loose game-dir Lua is
-- loaded like any other, no mod to enable).

local ITEM_TEXT = "UPDATE PZ OPTIMIZATION"   -- the stock items are capitals (UI_mainscreen_* translations)
local ITEM_RESTART = "RESTART TO FINISH THE UPDATE"

local function perf()
    return getPerformance()
end

local function state()
    local ok, s = pcall(function() return perf():getPzoptUpdateState() end)
    if not ok then return "idle" end
    return s
end

-- --- the dialog ----------------------------------------------------------------------------------

PzoptUpdateDialog = ISPanelJoypad:derive("PzoptUpdateDialog")
PzoptUpdateDialog.instance = nil

local FONT_HGT_SMALL = getTextManager():getFontHeight(UIFont.Small)
local FONT_HGT_MEDIUM = getTextManager():getFontHeight(UIFont.Medium)
local PAD = 12
local BTN_HGT = math.max(25, FONT_HGT_SMALL + 3 * 2)
local BAR_HGT = 14

-- Release notes come as GitHub markdown; the rich text panel only knows its own tags, so the angle
-- brackets go and every line break becomes a <LINE>.
local function richNotes(notes)
    if not notes or notes == "" then return "" end
    notes = notes:gsub("\r", ""):gsub("[<>]", ""):gsub("%s+$", "")
    if #notes > 1200 then notes = notes:sub(1, 1200) .. "..." end
    return (notes:gsub("\n", " <LINE> "))
end

function PzoptUpdateDialog:new(x, y, width, height)
    local o = ISPanelJoypad:new(x, y, width, height)
    setmetatable(o, self)
    self.__index = self
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0.85 }
    o.borderColor = { r = 1, g = 1, b = 1, a = 0.5 }
    o.moveWithMouse = true
    o.shownState = nil
    return o
end

function PzoptUpdateDialog:createChildren()
    ISPanelJoypad.createChildren(self)
    local textTop = PAD + FONT_HGT_MEDIUM + PAD
    local textHgt = self.height - textTop - PAD - BAR_HGT - PAD - BTN_HGT - PAD
    self.text = ISRichTextPanel:new(PAD, textTop, self.width - PAD * 2, textHgt)
    self.text:initialise()
    self.text.background = false
    self.text.clip = true
    self.text.autosetheight = false
    self.text.marginLeft = 0
    self.text.marginTop = 0
    self.text.marginRight = 0
    self.text:addScrollBars()
    self:addChild(self.text)

    local btnY = self.height - PAD - BTN_HGT
    self.primary = ISButton:new(0, btnY, 150, BTN_HGT, "", self, PzoptUpdateDialog.onPrimary)
    self.primary:initialise()
    self.primary:instantiate()
    self.primary:enableAcceptColor()
    self:addChild(self.primary)

    self.secondary = ISButton:new(0, btnY, 110, BTN_HGT, "", self, PzoptUpdateDialog.onSecondary)
    self.secondary:initialise()
    self.secondary:instantiate()
    self:addChild(self.secondary)

    self:refresh(true)
end

-- Button labels, text and layout for the current updater state; only rebuilt when the state changes.
function PzoptUpdateDialog:refresh(force)
    local s = state()
    if s == self.shownState and not force then return end
    self.shownState = s
    local p = perf()
    local installed = p:getPzoptUpdateInstalledCommit()
    local tag = p:getPzoptUpdateTag()
    local published = p:getPzoptUpdatePublished()
    local head = "Installed build: " .. installed .. " <LINE> Available: " .. tag
    if published ~= "" then head = head .. " (published " .. published .. ")" end
    local body
    if s == "available" then
        if p:canPzoptUpdateInstall() then
            body = head .. " <LINE> <LINE> "
                .. "Update now downloads the release zip, replaces the installed files (the ones listed in "
                .. "pzopt-installed.txt; the game's own files are never touched) and asks to quit: the new classes load "
                .. "on the next launch. Saves and options stay as they are."
            self.primary:setTitle("Update now")
            self.secondary:setTitle("Later")
        else
            body = head .. " <LINE> <LINE> "
                .. "This copy was not installed by install.sh / install.ps1 (no pzopt-installed.txt in the game folder), "
                .. "so it cannot replace its own files. Get the new zip from the release page and unpack it by hand, "
                .. "or install it once with the installer."
            self.primary:setTitle("Open release page")
            self.secondary:setTitle("Close")
        end
        local notes = richNotes(p:getPzoptUpdateNotes())
        if notes ~= "" then body = body .. " <LINE> <LINE> <RGB:0.8,0.8,0.8> " .. notes end
    elseif s == "downloading" then
        body = head .. " <LINE> <LINE> Downloading the release zip..."
        self.primary:setTitle("Hide")
        self.secondary:setTitle("")
    elseif s == "installing" then
        body = head .. " <LINE> <LINE> Replacing the installed files..."
        self.primary:setTitle("Hide")
        self.secondary:setTitle("")
    elseif s == "installed" then
        body = head .. " <LINE> <LINE> <RGB:0.6,1,0.6> " .. p:getPzoptUpdateMessage() .. " <RGB:1,1,1> <LINE> <LINE> "
            .. "The game keeps running the previous build until it restarts. Quit now and launch it again to load the update."
        self.primary:setTitle("Quit game")
        self.secondary:setTitle("Later")
    elseif s == "error" then
        body = head .. " <LINE> <LINE> <RGB:1,0.6,0.6> " .. (p:getPzoptUpdateMessage():gsub("[<>]", "")) .. " <RGB:1,1,1> <LINE> <LINE> "
            .. "Nothing was changed if the download failed; if the file swap failed, run the installer again "
            .. "(install.sh / install.ps1, --uninstall first). The release page has the zip."
        self.primary:setTitle("Open release page")
        self.secondary:setTitle("Close")
    else
        body = "No update is offered right now."
        self.primary:setTitle("Close")
        self.secondary:setTitle("")
    end
    self.text.text = body
    self.text:paginate()

    -- buttons centred, the second one only when it has a title
    local w1 = math.max(150, getTextManager():MeasureStringX(UIFont.Small, self.primary:getTitle()) + 24)
    self.primary:setWidth(w1)
    if self.secondary:getTitle() ~= "" then
        local w2 = math.max(110, getTextManager():MeasureStringX(UIFont.Small, self.secondary:getTitle()) + 24)
        self.secondary:setWidth(w2)
        self.secondary:setVisible(true)
        local total = w1 + PAD + w2
        self.primary:setX((self.width - total) / 2)
        self.secondary:setX(self.primary:getX() + w1 + PAD)
    else
        self.secondary:setVisible(false)
        self.primary:setX((self.width - w1) / 2)
    end
    self:syncJoypadButtons()
end

-- Controller: A is the first button and B the second one, like the stock yes / no modals; with a single
-- button ("Hide" / "Close") B closes too. The glyphs follow the buttons whenever the state changes.
function PzoptUpdateDialog:syncJoypadButtons()
    if not self.joyfocus then return end
    self:setISButtonForA(self.primary)
    if self.secondary:isVisible() then
        self:setISButtonForB(self.secondary)
    else
        self.ISButtonB = nil
        self.secondary:clearJoypadButton()
    end
end

function PzoptUpdateDialog:onGainJoypadFocus(joypadData)
    ISPanelJoypad.onGainJoypadFocus(self, joypadData)
    self.joypadButtons = {}
    self:syncJoypadButtons()
end

function PzoptUpdateDialog:onLoseJoypadFocus(joypadData)
    ISPanelJoypad.onLoseJoypadFocus(self, joypadData)
    self.ISButtonA = nil
    self.ISButtonB = nil
    self.primary:clearJoypadButton()
    self.secondary:clearJoypadButton()
end

function PzoptUpdateDialog:onJoypadDown(button, joypadData)
    if button == Joypad.BButton and not self.ISButtonB then
        self:close()
        return
    end
    ISPanelJoypad.onJoypadDown(self, button, joypadData)
end

-- the D-pad scrolls the release notes (three mouse-wheel notches)
function PzoptUpdateDialog:onJoypadDirUp(joypadData)
    self.text:setYScroll(self.text:getYScroll() + 18 * 3)
end

function PzoptUpdateDialog:onJoypadDirDown(joypadData)
    self.text:setYScroll(self.text:getYScroll() - 18 * 3)
end

function PzoptUpdateDialog:prerender()
    ISPanelJoypad.prerender(self)
    self:refresh(false)
    self:drawText("PZ Optimization update", PAD, PAD, 1, 1, 1, 1, UIFont.Medium)
    -- progress bar above the buttons: the download share, full while the files swap, green when done
    local s = self.shownState
    local barY = self.height - PAD - BTN_HGT - PAD - BAR_HGT
    local barW = self.width - PAD * 2
    self:drawRectBorder(PAD, barY, barW, BAR_HGT, 0.6, 1, 1, 1)
    local frac, r, g, b = 0, 0.35, 0.55, 0.9
    if s == "downloading" then
        frac = perf():getPzoptUpdateProgress() / 100
    elseif s == "installing" then
        frac, r, g, b = 1, 0.9, 0.75, 0.3
    elseif s == "installed" then
        frac, r, g, b = 1, 0.3, 0.8, 0.3
    elseif s == "error" then
        frac, r, g, b = 1, 0.8, 0.3, 0.3
    end
    if frac > 0 then
        self:drawRect(PAD + 1, barY + 1, (barW - 2) * frac, BAR_HGT - 2, 0.9, r, g, b)
    end
    local label = nil
    if s == "downloading" then
        label = "downloading " .. perf():getPzoptUpdateProgress() .. " %"
    elseif s == "installing" then
        label = "installing"
    end
    if label then
        local lw = getTextManager():MeasureStringX(UIFont.Small, label)
        self:drawText(label, self.width / 2 - lw / 2, barY + (BAR_HGT - FONT_HGT_SMALL) / 2 - 1, 1, 1, 1, 1, UIFont.Small)
    end
end

function PzoptUpdateDialog:onPrimary()
    local s = self.shownState
    if s == "available" then
        if perf():canPzoptUpdateInstall() then
            if not perf():pzoptUpdateInstall() then
                print("[pzopt] update: install refused (state " .. state() .. ")")
            end
            self:refresh(true)
        else
            openUrl(perf():getPzoptUpdatePageUrl())
        end
    elseif s == "installed" then
        self:close()
        MainScreen.instance:quitToDesktop()
    elseif s == "error" then
        openUrl(perf():getPzoptUpdatePageUrl())
    else
        self:close()
    end
end

function PzoptUpdateDialog:onSecondary()
    self:close()
end

function PzoptUpdateDialog:onKeyRelease(key)
    if key == Keyboard.KEY_ESCAPE then
        self:close()
        return true
    end
end

function PzoptUpdateDialog:close()
    self:setVisible(false)
    self:removeFromUIManager()
    PzoptUpdateDialog.instance = nil
    local ms = MainScreen.instance
    if ms and ms.bottomPanel then
        ms.bottomPanel:setVisible(true)
    end
    -- the controller's focus goes back where it came from (the menu, whose focus reset lands on the default
    -- item) and then onto the update item, so a "Later" leaves the cursor where the player pressed A
    local joypadData = self.joyfocus
    if joypadData and joypadData.focus == self then
        joypadData.focus = self.prevFocus
        updateJoypadFocus(joypadData)
        if ms and joypadData.focus == ms and ms.joyfocus and ms.pzoptUpdateOption then
            if ms:setJoypadFocus(ms.pzoptUpdateOption, joypadData) then
                ms:updateBottomPanelButtons()
            end
        end
    end
end

function PzoptUpdateDialog.show()
    if PzoptUpdateDialog.instance then
        PzoptUpdateDialog.instance:close()
    end
    local width = math.min(560, getCore():getScreenWidth() - 40)
    local height = math.min(480, getCore():getScreenHeight() - 40)
    local x = (getCore():getScreenWidth() - width) / 2
    local y = (getCore():getScreenHeight() - height) / 2
    local dlg = PzoptUpdateDialog:new(x, y, width, height)
    dlg:initialise()
    dlg:addToUIManager()
    dlg:setAlwaysOnTop(true)
    dlg:bringToTop()
    PzoptUpdateDialog.instance = dlg
    -- like the stock quit dialog: the menu items go while a dialog is up, and a controller's focus moves
    -- to the dialog (the menu would otherwise keep A for itself)
    MainScreen.instance.bottomPanel:setVisible(false)
    local joypadData = JoypadState.getMainMenuJoypad()
    if joypadData then
        dlg.prevFocus = joypadData.focus
        joypadData.focus = dlg
        updateJoypadFocus(joypadData)
    end
end

-- --- the menu item -------------------------------------------------------------------------------

-- Clickable while an update is offered, being installed, installed, or failed (the dialog explains);
-- greyed out and inert while the check runs, when the build is current, or when the check itself failed.
local function itemEnabled(s)
    return s == "available" or s == "downloading" or s == "installing" or s == "installed"
        or (s == "error" and perf():getPzoptUpdateTag() ~= "")
end

local function onItemClick(item, x, y)
    if not item.pzoptEnabled then return end
    local ms = MainScreen.instance
    if ms.delay > 0 or ms.tutorialButton or ms.checkSavefileModal then return end
    getSoundManager():playUISound("UIActivateMainMenuItem")
    PzoptUpdateDialog.show()
end

-- The version line under the item: the installed build's commit, and "-> <commit>" of the offered
-- release (tag win-<revision>-<commit>) while one is offered, downloading, installed or failed.
local function versionText()
    local p = perf()
    local text = "Version " .. p:getPzoptUpdateInstalledCommit()
    local tag = p:getPzoptUpdateTag()
    if tag ~= "" then
        text = text .. " -> " .. (tag:match("([^-]+)$") or tag)
    end
    return text
end

-- The stock hover fade only while enabled; a disabled item is a plain grey label. The item is taller
-- than the stock ones by the version line: the label text stays centred in the stock row height (the
-- hover rectangle covers both) and the version is drawn under it in the small font.
local function itemPrerender(self)
    local fullHgt = self.height
    self.height = self.pzoptRowHgt
    if self.fade then
        MainScreen.prerenderBottomPanelLabel(self)
    else
        ISLabel.prerender(self)
    end
    self.height = fullHgt
    if self.pzoptVersion then
        local a = self.pzoptEnabled and 0.75 or 0.45
        self:drawText(self.pzoptVersion, 0, self.pzoptRowHgt - 6, a, a, a, 1, UIFont.Small)
    end
end

-- Adds the label to the column right after the stock instantiate, between Credits and Exit (Exit and
-- the panel move down one row). Same metrics as the stock labels in MainScreen:instantiate. Always
-- there, like the stock items; enabled or greyed by the updater's state (syncItem).
local function addItem(self)
    if self.inGame or not self.creditOption or not self.exitOption or self.pzoptUpdateOption then return end
    local rowHgt = getTextManager():getFontHeight(UIFont.Large) + 8 * 2
    local labelHgt = rowHgt + FONT_HGT_SMALL   -- the version line starts 6 px above the stock row's bottom
    local label = ISLabel:new(0, self.creditOption:getBottom(), labelHgt, ITEM_TEXT, 1, 1, 1, 1, UIFont.Large, true)
    label.internal = "PZOPT_UPDATE"
    label:initialise()
    label.onMouseDown = onItemClick
    label.prerender = itemPrerender
    label.pzoptRowHgt = rowHgt
    label.pzoptEnabled = false
    label:setColor(0.45, 0.45, 0.45)
    label:setVisible(false)
    self.exitOption:setY(self.exitOption:getY() + labelHgt)
    self.bottomPanel:setHeight(self.bottomPanel:getHeight() + labelHgt)
    self.maxMenuItemWidth = math.max(self.maxMenuItemWidth or 0, getTextManager():MeasureStringX(UIFont.Large, ITEM_RESTART))
    label:setWidth(self.maxMenuItemWidth)
    self.bottomPanel:addChild(label)
    self.pzoptUpdateOption = label
    pcall(function() perf():pzoptUpdateCheck() end)
    print("[pzopt] update: main menu item added")
end

-- Controller. The D-pad walks self.joypadButtonsY, which the stock MainScreen:onGainJoypadFocus rebuilds
-- from its own list of labels (so a controller went from Credits straight to Exit), and A goes through
-- onMenuItemMouseDownMainMenu, which only knows the stock `internal` names. The label gets its own row
-- right after Credits while it is enabled and visible; a greyed item has no row, the D-pad skips it like
-- the mouse ignores it. Runs after every stock rebuild and once per frame while the menu holds the focus.
local function joypadRowOf(rows, element)
    if not element then return nil end
    for i, row in ipairs(rows) do
        if row[1] == element then return i end
    end
    return nil
end

local function syncJoypadRow(self)
    local label = self.pzoptUpdateOption
    local rows = self.joypadButtonsY
    if not label or not rows then return end
    local at = joypadRowOf(rows, label)
    local want = label.pzoptEnabled and label:isVisible()
    if want and not at then
        local pos = joypadRowOf(rows, self.creditOption)
        if pos then
            pos = pos + 1
        else
            pos = joypadRowOf(rows, self.exitOption) or (#rows + 1)
        end
        table.insert(rows, pos, { label })
        if (self.joypadIndexY or 0) >= pos then
            self.joypadIndexY = self.joypadIndexY + 1
        end
    elseif at and not want then
        table.remove(rows, at)
        label:setJoypadFocused(false)
        if self.joypadIndexY == at then
            -- the focused item just went inert: the cursor moves to the row that took its place
            local row = rows[math.min(at, #rows)]
            if row then
                self:setJoypadFocus(row[1], self.joyfocus)
                self:updateBottomPanelButtons()
            end
        elseif (self.joypadIndexY or 0) > at then
            self.joypadIndexY = self.joypadIndexY - 1
        end
    end
end

-- Once per frame: enabled state, colour and text follow the updater; the item is shown whenever Exit
-- is, i.e. after the intro fade and not while a stock dialog hid the panel.
local function syncItem(self)
    local label = self.pzoptUpdateOption
    if not label then return end
    local s = state()
    local enabled = itemEnabled(s)
    if enabled ~= label.pzoptEnabled then
        label.pzoptEnabled = enabled
        if enabled then
            label.fade = UITransition.new()
            label.fade:setFadeIn(false)
            label:setColor(1, 1, 1)
            print("[pzopt] update: main menu item enabled (" .. s .. ", " .. perf():getPzoptUpdateTag() .. ")")
        else
            if self.overBottomPanelButton == label then self.overBottomPanelButton = nil end
            label.fade = nil
            label:setColor(0.45, 0.45, 0.45)
        end
    end
    local text = ITEM_TEXT
    if s == "downloading" then
        text = "UPDATING... " .. perf():getPzoptUpdateProgress() .. " %"
    elseif s == "installing" then
        text = "UPDATING... INSTALLING"
    elseif s == "installed" then
        text = ITEM_RESTART
    elseif s == "error" and enabled then
        text = "UPDATE FAILED"
    end
    if label.name ~= text then
        label:setNameWithoutMoving(text)
        label:setWidth(self.maxMenuItemWidth or label:getWidth())
    end
    label.pzoptVersion = versionText()
    label:setVisible(self.exitOption:isVisible())
    if self.joyfocus then syncJoypadRow(self) end
end

local function install()
    if not MainScreen or MainScreen.pzoptUpdateItem then return end
    local ok, has = pcall(function() return getPerformance():hasPzoptOptions() end)
    if not ok or not has then
        print("[pzopt] update item: PerformanceSettings override not loaded or overrides disabled, item not added")
        return
    end
    MainScreen.pzoptUpdateItem = true
    local stockInstantiate = MainScreen.instantiate
    function MainScreen:instantiate(...)
        stockInstantiate(self, ...)
        local okAdd, err = pcall(addItem, self)
        if not okAdd then print("[pzopt] update item: failed: " .. tostring(err)) end
    end
    local stockPrerender = MainScreen.prerender
    function MainScreen:prerender(...)
        stockPrerender(self, ...)
        if self.pzoptUpdateOption then
            local okSync, err = pcall(syncItem, self)
            if not okSync then
                print("[pzopt] update item: sync failed, item removed: " .. tostring(err))
                self.pzoptUpdateOption:setVisible(false)
                self.pzoptUpdateOption = nil
            end
        end
    end
    -- controller: the row right after the stock rebuild of the joypad list, and A on the item is the click
    local stockGainJoypadFocus = MainScreen.onGainJoypadFocus
    function MainScreen:onGainJoypadFocus(...)
        stockGainJoypadFocus(self, ...)
        if self.pzoptUpdateOption then pcall(syncJoypadRow, self) end
    end
    local stockJoypadDown = MainScreen.onJoypadDown
    function MainScreen:onJoypadDown(button, ...)
        local label = self.pzoptUpdateOption
        if label and button == Joypad.AButton and self.joypadButtons and self.joypadButtons[self.joypadIndex] == label then
            onItemClick(label, 0, 0)
            return
        end
        return stockJoypadDown(self, button, ...)
    end
end

install()
Events.OnGameBoot.Add(install)
