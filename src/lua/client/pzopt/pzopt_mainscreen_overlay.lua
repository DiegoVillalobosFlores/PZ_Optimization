-- pzopt: "Show / Hide performance overlay" item in the main menu and in the pause menu.
--  The same toggle as the key binding (Options > Key Bindings, default F9) and the controller chord
--  L3 + R3, through the overridden
--  PerformanceSettings (pzopt.Overlay.toggle): one more item in the style of the stock ones (ISLabel,
--  UIFont.Large, the same hover fade and sounds) right below Options in both menus. Its text follows
--  the overlay's state and names both shortcuts (the bound key, read each frame, so a rebind shows up).
--  With overlaySampling off the overlay cannot show this session: the item stays
--  "SHOW" and a click shows the overlay's own notice (tick the box, restart), like the key does.
--  Controller: the item has its own row in the menu's joypad list right after Options (the D-pad
--  reaches it like any stock item), A toggles.
--  Not added when every optimization is off (enabled=false): the overlay is inert then.
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/client/pzopt/ (loose game-dir Lua is
-- loaded like any other, no mod to enable).

local TEXT_SHOW = "SHOW PERFORMANCE OVERLAY"   -- the stock items are capitals (UI_mainscreen_* translations)
local TEXT_HIDE = "HIDE PERFORMANCE OVERLAY"
local BIND = "Toggle performance overlay"      -- pzopt_keybinding.lua
local PAD_SHORTCUT = "L3 + R3"                 -- pzopt.Overlay's controller chord

-- "(F9 / L3 + R3)" with the key the binding holds now; just the chord when the binding is cleared.
local function shortcuts()
    local ok, name = pcall(function()
        local key = getCore():getKey(BIND)
        if not key or key <= 0 then return nil end
        return Keyboard.getKeyName(key)
    end)
    if ok and name and name ~= "" then
        return "(" .. string.upper(name) .. " / " .. PAD_SHORTCUT .. ")"
    end
    return "(" .. PAD_SHORTCUT .. ")"
end

local function itemText(visible)
    return (visible and TEXT_HIDE or TEXT_SHOW) .. "  " .. shortcuts()
end

local function perf()
    return getPerformance()
end

local function overlayVisible()
    local ok, v = pcall(function() return perf():isPzoptOverlayVisible() end)
    return ok and v
end

local function labelHeight()
    return getTextManager():getFontHeight(UIFont.Large) + 8 * 2
end

local function onItemClick(item, x, y)
    local ms = MainScreen.instance
    if not ms or (ms.delay or 0) > 0 or ms.tutorialButton or ms.checkSavefileModal then return end
    getSoundManager():playUISound("UIActivateMainMenuItem")
    local ok, err = pcall(function() perf():togglePzoptOverlay() end)
    if not ok then print("[pzopt] overlay item: toggle failed: " .. tostring(err)) end
end

-- Adds the label right below Options after the stock instantiate; every stock item under Options (and
-- the update item, whichever file ran first) moves down one row. Same metrics as the stock labels in
-- MainScreen:instantiate; the hover fade is set up here because the stock loop that gives the labels
-- theirs ran before the label existed.
local function addItem(self)
    local options = self.optionsOption
    if not options or not self.bottomPanel or self.pzoptOverlayOption then return end
    local labelHgt = labelHeight()
    local y = options:getBottom()
    for _, child in pairs(self.bottomPanel:getChildren()) do
        if child.Type == "ISLabel" and child.internal and child ~= options and child:getY() >= y then
            child:setY(child:getY() + labelHgt)
        end
    end
    local label = ISLabel:new(options:getX(), y, labelHgt, itemText(false), 1, 1, 1, 1, UIFont.Large, true)
    label.internal = "PZOPT_OVERLAY"
    label:initialise()
    label.onMouseDown = onItemClick
    label.fade = UITransition.new()
    label.fade:setFadeIn(false)
    label.prerender = MainScreen.prerenderBottomPanelLabel
    label:setVisible(options:isVisible())
    self.bottomPanel:setHeight(self.bottomPanel:getHeight() + labelHgt)
    local textW = math.max(getTextManager():MeasureStringX(UIFont.Large, itemText(false)),
        getTextManager():MeasureStringX(UIFont.Large, itemText(true)))
    self.maxMenuItemWidth = math.max(self.maxMenuItemWidth or 0, textW)
    label:setWidth(math.max(self.bottomPanel:getWidth(), textW))
    self.bottomPanel:addChild(label)
    self.pzoptOverlayOption = label
    print("[pzopt] overlay item: added to the " .. (self.inGame and "pause" or "main") .. " menu")
end

-- Controller. The D-pad walks self.joypadButtonsY, which the stock MainScreen:onGainJoypadFocus rebuilds
-- from its own list of labels, and A goes through onMenuItemMouseDownMainMenu, which only knows the stock
-- `internal` names. The label gets its own row right after Options while it is visible; runs after every
-- stock rebuild and once per frame while the menu holds the focus (the main menu's items appear after the
-- intro fade, later than the first focus).
local function joypadRowOf(rows, element)
    if not element then return nil end
    for i, row in ipairs(rows) do
        if row[1] == element then return i end
    end
    return nil
end

local function syncJoypadRow(self)
    local label = self.pzoptOverlayOption
    local rows = self.joypadButtonsY
    if not label or not rows then return end
    local at = joypadRowOf(rows, label)
    local want = label:isVisible()
    if want and not at then
        local pos = joypadRowOf(rows, self.optionsOption)
        if not pos then return end -- Options not in the list yet (hidden): wait for it
        pos = pos + 1
        table.insert(rows, pos, { label })
        if (self.joypadIndexY or 0) >= pos then
            self.joypadIndexY = self.joypadIndexY + 1
        end
    elseif at and not want then
        table.remove(rows, at)
        label:setJoypadFocused(false)
        if self.joypadIndexY == at then
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

-- Once per frame: the text follows the overlay (the key and the chord toggle it too) and the key binding, the item is shown whenever
-- Options is, i.e. after the main menu's intro fade and not while a stock screen hid it.
local function syncItem(self)
    local label = self.pzoptOverlayOption
    local text = itemText(overlayVisible())
    if label.name ~= text then
        local w = label:getWidth()
        label:setNameWithoutMoving(text)
        label:setWidth(math.max(w, getTextManager():MeasureStringX(UIFont.Large, text)))
    end
    label:setVisible(self.optionsOption:isVisible())
    if self.joyfocus then syncJoypadRow(self) end
end

-- The multiplayer pause menu re-lays out Options, Exit and Quit to desktop every frame in the stock
-- MainScreen:render (the invite item comes and goes): the item goes back below Options, the rest one row down.
local function relayoutClientPause(self)
    local label = self.pzoptOverlayOption
    local labelHgt = label:getHeight()
    label:setY(self.optionsOption:getBottom())
    if self.exitOption then self.exitOption:setY(self.exitOption:getY() + labelHgt) end
    if self.quitToDesktopOption then
        self.quitToDesktopOption:setY(self.quitToDesktopOption:getY() + labelHgt)
        self.bottomPanel:setHeight(self.quitToDesktopOption:getBottom())
    end
end

local function dropItem(self, what, err)
    print("[pzopt] overlay item: " .. what .. " failed, item removed: " .. tostring(err))
    self.pzoptOverlayOption:setVisible(false)
    self.pzoptOverlayOption = nil
end

local function install()
    if not MainScreen or MainScreen.pzoptOverlayItem then return end
    -- the overlay works with the optimizations switched off (pzopt.Overlay needs only a matching build)
    local ok, on = pcall(function() return getPerformance():hasPzoptOptions() end)
    if not ok or not on then
        print("[pzopt] overlay item: PerformanceSettings override not loaded or build mismatch, item not added")
        return
    end
    MainScreen.pzoptOverlayItem = true
    local stockInstantiate = MainScreen.instantiate
    function MainScreen:instantiate(...)
        stockInstantiate(self, ...)
        local okAdd, err = pcall(addItem, self)
        if not okAdd then print("[pzopt] overlay item: failed: " .. tostring(err)) end
    end
    local stockPrerender = MainScreen.prerender
    function MainScreen:prerender(...)
        stockPrerender(self, ...)
        if self.pzoptOverlayOption then
            local okSync, err = pcall(syncItem, self)
            if not okSync then dropItem(self, "sync", err) end
        end
    end
    local stockRender = MainScreen.render
    function MainScreen:render(...)
        stockRender(self, ...)
        if self.pzoptOverlayOption and self.inGame and isClient() then
            local okLay, err = pcall(relayoutClientPause, self)
            if not okLay then dropItem(self, "layout", err) end
        end
    end
    -- controller: the row right after the stock rebuild of the joypad list, and A on the item is the click
    local stockGainJoypadFocus = MainScreen.onGainJoypadFocus
    function MainScreen:onGainJoypadFocus(...)
        stockGainJoypadFocus(self, ...)
        if self.pzoptOverlayOption then pcall(syncJoypadRow, self) end
    end
    local stockJoypadDown = MainScreen.onJoypadDown
    function MainScreen:onJoypadDown(button, ...)
        local label = self.pzoptOverlayOption
        if label and button == Joypad.AButton and self.joypadButtons and self.joypadButtons[self.joypadIndex] == label then
            onItemClick(label, 0, 0)
            return
        end
        return stockJoypadDown(self, button, ...)
    end
end

install()
Events.OnGameBoot.Add(install)
