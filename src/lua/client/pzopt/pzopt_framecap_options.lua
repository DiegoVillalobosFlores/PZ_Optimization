-- pzopt: frame-rate combos in Display options.
--  * The stock "Framerate" combo gains 500, 430, 400, 330 and 300 fps entries above the stock 244.
--    Core.setFramerate only knows the stock indices and options.ini cannot hold a cap above 244
--    (Core clamps it to 24..244 on load and on save), so the combo applies its value through
--    PerformanceSettings:setGameFramerate, which pzopt.FrameCap persists in framecap.ini.
--  * A second "Menu framerate" combo right under it. The setting lives in Java: the overridden
--    PerformanceSettings forwards to pzopt.FrameCap, which persists it in Zomboid/pzopt/framecap.ini.
--    Index: 1 = same as in-game, 2 = uncapped, 3.. = the fps table below.
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/client/pzopt/ (loose game-dir Lua is
-- loaded like any other, no mod to enable).

-- Same order as pzopt.FrameCap.FPS_TABLE; the two must agree.
local FPS_TABLE = { 500, 430, 400, 330, 300, 244, 240, 165, 144, 120, 95, 90, 75, 60, 55, 45, 30, 24 }

local function fpsLabels(prefix)
    local t = {}
    for _, v in ipairs(prefix) do table.insert(t, v) end
    for _, fps in ipairs(FPS_TABLE) do table.insert(t, tostring(fps)) end
    return t
end

local function indexOfFps(fps, labels)
    for i, label in ipairs(labels) do
        if tonumber(label) == fps then return i end
    end
    return nil
end

-- Replaces the stock 'framerate' GameOption's toUI/apply, which hard-code the stock indices.
local function extendGameOption(option)
    function option.toUI(self)
        local box = self.control
        if getPerformance():isFramerateUncapped() then
            box.selected = 1
        else
            box.selected = indexOfFps(getPerformance():getFramerate(), box.options)
                or indexOfFps(60, box.options)
        end
    end
    function option.apply(self)
        local box = self.control
        local fps = tonumber(box.options[box.selected])
        if box.selected == 1 then
            getPerformance():setGameFramerate(0) -- uncapped, lock kept
        elseif fps then
            getPerformance():setGameFramerate(fps)
        end
    end
end

local function install()
    if not MainOptions or MainOptions.pzoptMenuFramerate then return end
    local ok, has = pcall(function() return getPerformance():getMenuFramerateChoices() > 0 end)
    if not ok or not has then
        print("[pzopt] menu framerate: PerformanceSettings override not loaded, combo not added")
        return
    end
    MainOptions.pzoptMenuFramerate = true
    local stockAddCombo = MainOptions.addCombo
    local frameLabel = getText("UI_optionscreen_framerate")
    function MainOptions:addCombo(x, y, w, h, name, options, selected, target, onchange)
        if name ~= frameLabel or self.pzoptMenuCombo then
            return stockAddCombo(self, x, y, w, h, name, options, selected, target, onchange)
        end
        -- FrameCap always sets the SystemDisabler flag, so the stock list starts with "Uncapped".
        local uncappedLabel = getText("UI_optionscreen_Uncapped")
        local extended = fpsLabels({ uncappedLabel })
        local combo = stockAddCombo(self, x, y, w, h, name, extended, selected, target, onchange)
        local menu = stockAddCombo(self, x, y, w, h, PzoptT("framecap.menu", "Menu framerate"),
                                   fpsLabels({ PzoptT("framecap.sameAsGame", "Same as in-game"), uncappedLabel }), 1, target, onchange)
        self.pzoptMenuCombo = menu
        -- The stock code creates the 'framerate' GameOption after this returns; catch it on add.
        local stockAdd = self.gameOptions.add
        self.gameOptions.add = function(opts, option)
            if option and option.name == 'framerate' and option.control == combo then
                extendGameOption(option)
                opts.add = stockAdd
                -- Register the menu option right after it so it sits in order.
                local r = stockAdd(opts, option)
                local gameOption = GameOption:new('pzoptMenuFramerate', menu)
                function gameOption.toUI(self)
                    self.control.selected = getPerformance():getMenuFramerateIndex()
                end
                function gameOption.apply(self)
                    local box = self.control
                    if box.options[box.selected] then
                        getPerformance():setMenuFramerateIndex(box.selected)
                    end
                end
                stockAdd(opts, gameOption)
                return r
            end
            return stockAdd(opts, option)
        end
        return combo
    end
end

install()
Events.OnGameBoot.Add(install)
