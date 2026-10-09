-- Send-to-Kindle style delivery for RemoteLibrary: books dropped into the
-- cloud folder (e.g. from the Send to KOReader phone app) are picked up and
-- downloaded automatically.
-- Triggers: every POLL_INTERVAL while awake and online, Wi-Fi connected,
-- resume from sleep, shortly after start.
-- Scan and download run in a subprocess so the UI never blocks. Only books
-- that are new since the previous scan are downloaded; older cloud-only
-- books stay cloud-only.
local DataStorage = require("datastorage")
local Device = require("device")
local NetworkMgr = require("ui/network/manager")
local Notification = require("ui/widget/notification")
local PluginLoader = require("pluginloader")
local UIManager = require("ui/uimanager")
local ffiutil = require("ffi/util")
local logger = require("logger")
local util = require("util")

local MIN_INTERVAL = 60 -- seconds between scans
local POLL_INTERVAL = 120 -- background poll while awake
local MAX_DOWNLOAD = 300 * 1024 * 1024
local SUBPROCESS_TIMEOUT = 900
local MAP_FILE = DataStorage:getSettingsDir() .. "/remotelibrary_map.lua"
local STATUS_FILE = DataStorage:getSettingsDir() .. "/remotelibrary_autorefresh.status"

local last_scan = 0
local running = false
local current_rl -- most recently created RemoteLibrary instance

-- rel path -> { name, url, size }
local function collectFiles(node, prefix, out)
    if type(node) ~= "table" then return out end
    for _, f in ipairs(node.files or {}) do
        out[prefix .. f.name] = { name = f.name, url = f.url, size = f.filesize }
    end
    for name, sub in pairs(node.folders or {}) do
        collectFiles(sub, prefix .. name:gsub("/+$", "") .. "/", out)
    end
    return out
end

local function readMapFiles()
    local ok, tree = pcall(dofile, MAP_FILE)
    if ok and type(tree) == "table" then return collectFiles(tree, "", {}) end
end

-- RemoteLibrary patches lfs to report cloud proxies as real files, so test
-- for real files with io.open.
local function realFileExists(path)
    local f = io.open(path, "rb")
    if f then f:close() return true end
    return false
end

local function refreshFileChooser()
    local FileManager = require("apps/filemanager/filemanager")
    local fm = FileManager.instance
    if fm and fm.file_chooser then
        fm.file_chooser:refreshPath()
    end
end

local function bookTitle(name)
    return "《" .. name:gsub("%.[^.]+$", "") .. "》"
end

local function titles(list)
    local shown = {}
    for i = 1, math.min(#list, 3) do shown[i] = bookTitle(list[i]) end
    return table.concat(shown, "、") .. (#list > 3 and " 等" or "")
end

-- Runs in the subprocess.
local function download(dir, home, new)
    local http = require("socket.http")
    local ltn12 = require("ltn12")
    local socket = require("socket")
    local socketutil = require("socketutil")
    -- Map urls are relative to the server address. RemoteLibrary <= 0.2.0's
    -- fast scan stores server-absolute urls instead when the address has a
    -- path (e.g. /dav); see dani84bs/RemoteLibrary.koplugin#7.
    local base = dir.address:gsub("/+$", "")
    local done = {}
    for _, item in ipairs(new) do
        local target = home .. "/" .. item.rel
        if not realFileExists(target) and (item.size or 0) <= MAX_DOWNLOAD then
            local parent = target:match("(.*)/")
            os.execute(string.format("mkdir -p %q", parent))
            local tmp = target .. ".part"
            local fh = io.open(tmp, "wb")
            if fh then
                socketutil:set_timeout(socketutil.FILE_BLOCK_TIMEOUT, socketutil.FILE_TOTAL_TIMEOUT)
                local code = socket.skip(1, http.request{
                    url = base .. "/" .. util.urlEncode(item.url, "/"),
                    method = "GET",
                    sink = ltn12.sink.file(fh),
                    user = dir.username,
                    password = dir.password,
                })
                socketutil:reset_timeout()
                if code == 200 and os.rename(tmp, target) then
                    table.insert(done, item.name)
                else
                    os.remove(tmp)
                end
            end
        end
    end
    return done
end

-- A forked child inherits every fd of its parent, listening sockets included
-- (e.g. HttpInspector's :8080). While the child lives the port stays bound
-- even after the parent closes its copy, so re-listening on it fails with
-- EADDRINUSE. Point inherited listening sockets at /dev/null: dup2 keeps the
-- fd number taken, so a stale socket object being garbage-collected in the
-- child can't close an fd the child has since reused.
local function releaseInheritedListeners()
    local ffi = require("ffi")
    local C = ffi.C
    require("ffi/posix_h")
    pcall(ffi.cdef, "ssize_t readlink(const char *, char *, size_t);")
    local listening = {}
    for _, path in ipairs{ "/proc/net/tcp", "/proc/net/tcp6" } do
        local f = io.open(path, "r")
        if f then
            for line in f:lines() do
                local fields = {}
                for field in line:gmatch("%S+") do fields[#fields + 1] = field end
                if fields[4] == "0A" and fields[10] then -- 0A = TCP_LISTEN
                    listening[fields[10]] = true
                end
            end
            f:close()
        end
    end
    if not next(listening) then return end
    local devnull = C.open("/dev/null", C.O_RDWR)
    if devnull < 0 then return end
    local buf = ffi.new("char[64]")
    for name in require("libs/libkoreader-lfs").dir("/proc/self/fd") do
        local fd = tonumber(name)
        if fd and fd > 2 and fd ~= devnull then
            local len = C.readlink("/proc/self/fd/" .. name, buf, 63)
            local inode = len > 0 and ffi.string(buf, len):match("^socket:%[(%d+)%]$")
            if inode and listening[inode] then
                C.dup2(devnull, fd)
            end
        end
    end
    C.close(devnull)
end

local function refresh(rl, reason)
    local function skip(why)
        logger.dbg("remotelibrary-autorefresh: skip", reason, why)
    end
    if running or os.time() - last_scan < MIN_INTERVAL then return skip("throttled") end
    if Device.screen_saver_mode then return skip("asleep") end
    if not NetworkMgr:isConnected() then return skip("offline") end
    local Scanner = package.loaded["scanner"]
    local RemoteMap = package.loaded["remotemap"]
    if not (Scanner and RemoteMap and rl and rl.loadSettings) then
        return logger.warn("remotelibrary-autorefresh: RemoteLibrary modules not found")
    end
    rl:loadSettings()
    local dir = rl.settings:readSetting("cloudstorage_dir")
    if not dir or dir.type ~= "webdav" or rl.is_reloading then return skip("no webdav dir") end
    local home = G_reader_settings:readSetting("home_dir") or Device.home_dir
    if not home then return skip("no home dir") end

    running = true
    last_scan = os.time()
    local before = readMapFiles()
    os.remove(STATUS_FILE)

    local pid = ffiutil.runInSubProcess(function()
        releaseInheritedListeners()
        -- Only the single-request fast scan: the per-folder fallback needs the
        -- UI event loop, which a subprocess doesn't have.
        local tree
        local ok = pcall(Scanner.scan, {}, dir, {
            on_progress = function() end,
            on_fallback = function() error("no fast scan") end,
            on_done = function(t) tree = t end,
        }, function() return false end)
        if not ok or not tree or not RemoteMap.save(tree) then os.exit(1) end

        local after = collectFiles(tree, "", {})
        local new, gone = {}, false
        if before then
            for rel, f in pairs(after) do
                if not before[rel] then
                    f.rel = rel
                    table.insert(new, f)
                end
            end
            for rel in pairs(before) do
                if not after[rel] then gone = true break end
            end
        end
        table.sort(new, function(a, b) return a.rel < b.rel end)
        local downloaded = download(dir, home, new)

        local out = io.open(STATUS_FILE, "w")
        if out then
            out:write((#new > 0 or gone or not before) and "changed" or "same", "\n")
            for _, f in ipairs(new) do out:write("new\t", f.name, "\n") end
            for _, name in ipairs(downloaded) do out:write("got\t", name, "\n") end
            out:close()
        end
    end)
    if not pid then
        running = false
        return
    end

    local waited = 0
    local function poll()
        if not ffiutil.isSubProcessDone(pid) then
            waited = waited + 1
            if waited > SUBPROCESS_TIMEOUT then
                ffiutil.terminateSubProcess(pid)
                running = false
                logger.warn("remotelibrary-autorefresh: timed out")
                return
            end
            UIManager:scheduleIn(1, poll)
            return
        end
        running = false
        RemoteMap.invalidate()
        local f = io.open(STATUS_FILE, "r")
        if not f then
            logger.warn("remotelibrary-autorefresh: scan failed (" .. reason .. ")")
            return
        end
        local changed = f:read("*l") == "changed"
        local new, got = {}, {}
        for line in f:lines() do
            local kind, name = line:match("^(%a+)\t(.+)$")
            if kind == "new" then table.insert(new, name)
            elseif kind == "got" then table.insert(got, name) end
        end
        f:close()
        os.remove(STATUS_FILE)
        logger.info("remotelibrary-autorefresh:", reason, "new:", #new, "downloaded:", #got)

        if changed or #got > 0 then refreshFileChooser() end
        if #got > 0 then
            Notification:notify(string.format("已收到 %d 本新书：%s", #got, titles(got)),
                Notification.SOURCE_ALWAYS_SHOW)
        elseif #new > 0 then
            Notification:notify(string.format("云端书库新增 %d 本：%s", #new, titles(new)),
                Notification.SOURCE_ALWAYS_SHOW)
        end
    end
    UIManager:scheduleIn(1, poll)
end

local function periodic()
    refresh(current_rl, "poll")
    UIManager:scheduleIn(POLL_INTERVAL, periodic)
end
UIManager:scheduleIn(POLL_INTERVAL, periodic)

-- Attach event handlers to every RemoteLibrary instance (FileManager and
-- ReaderUI each get one) as they are created. PluginLoader names plugins
-- after their folder ("RemoteLibrary").
local orig_create = PluginLoader.createPluginInstance
function PluginLoader:createPluginInstance(plugin, attr)
    local ok, inst = orig_create(self, plugin, attr)
    if ok and tostring(plugin.name):lower() == "remotelibrary" and type(inst) == "table" then
        current_rl = inst
        -- Event handlers must not return true: other modules need these too.
        inst.onNetworkConnected = function(rl)
            UIManager:scheduleIn(3, function() refresh(rl, "network") end)
        end
        inst.onResume = function(rl)
            -- If Wi-Fi survived sleep; otherwise NetworkConnected follows.
            UIManager:scheduleIn(5, function() refresh(rl, "resume") end)
        end
        UIManager:scheduleIn(15, function() refresh(inst, "startup") end)
    end
    return ok, inst
end
