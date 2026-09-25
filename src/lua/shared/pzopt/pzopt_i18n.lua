-- pzopt: UI text in the game's language (issue #21). The English text stays at every call site as the source and
-- the fallback; pzopt.I18n (Java) looks the key up in media/pzopt/translate/<LANG>.json for the game's language
-- (current -> base -> English), so a language without a file, or a key its file lacks, shows the English.
--   PzoptT("tab.optimizations", "Optimizations")           -> the translation or "Optimizations"
--   PzoptT("preview.pinned", "(pinned by %1)", pinnedBy)   -> %1..%9 filled from the extra arguments
-- PzoptWrap(font, text, width[, maxLines, ellipsis]) wraps like TextManager:WrapText, and also text without spaces
-- (Chinese, Japanese): WrapText only breaks at spaces and hyphenates a longer run.

local cache = {}

function PzoptT(key, english, ...)
    local s = cache[key]
    if s == nil then
        s = english
        local ok, v = pcall(function() return getPerformance():getPzoptText(key, english) end)
        if ok and v then s = v end
        cache[key] = s
    end
    if select("#", ...) == 0 then return s end
    -- one pass, so a value that itself holds "%2" is never substituted again
    local args = { ... }
    return (string.gsub(s, "%%(%d)", function(d)
        local v = args[tonumber(d)]
        if v == nil then return "%" .. d end
        return tostring(v)
    end))
end

-- A character WrapText cannot break around: CJK ideographs, kana, hangul and full-width punctuation (U+2E80 and up).
local function isWide(c)
    return string.byte(c) >= 0x2E80
end

-- Kahlua strings are Java strings: string.sub / # count UTF-16 chars, so one CJK character is one step here.
local function hasWide(text)
    for i = 1, #text do
        if isWide(string.sub(text, i, i)) then return true end
    end
    return false
end

function PzoptWrap(font, text, width, maxLines, ellipsis)
    local tm = getTextManager()
    if not hasWide(text) then
        if maxLines then return tm:WrapText(font, text, width, maxLines, ellipsis or "...") end
        return tm:WrapText(font, text, width)
    end
    local lines = {}
    for para in string.gmatch(text, "[^\n]+") do
        local line, i, len = "", 1, #para
        while i <= len do
            -- the next unit: one wide character, or a run of narrow ones up to and including a space
            local j = i
            if not isWide(string.sub(para, i, i)) then
                while j < len and not isWide(string.sub(para, j + 1, j + 1)) and string.sub(para, j, j) ~= " " do j = j + 1 end
            end
            local unit = string.sub(para, i, j)
            if line ~= "" and tm:MeasureStringX(font, line .. unit) > width then
                table.insert(lines, line)
                line = string.gsub(unit, "^ +", "")
            else
                line = line .. unit
            end
            i = j + 1
        end
        table.insert(lines, line)
    end
    if maxLines and #lines > maxLines then
        local last = lines[maxLines]
        local dots = ellipsis or "..."
        while #last > 0 and tm:MeasureStringX(font, last .. dots) > width do last = string.sub(last, 1, #last - 1) end
        lines[maxLines] = last .. dots
        for k = #lines, maxLines + 1, -1 do table.remove(lines, k) end
    end
    return table.concat(lines, "\n")
end
