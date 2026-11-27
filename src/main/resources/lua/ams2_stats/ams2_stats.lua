--[[
  AMS2 Dedicated Server - Enhanced Stats Plugin (ams2-stats)
  Patched to key drivers by participantid (pid) instead of refid so multiplayer AI cars
  (which share the host's refid) are tracked individually with their own name, is_player, and vehicle_id.
--]]

local addon_storage = ...
---@diagnostic disable: undefined-global

local config       = addon_storage.config
local MAX_SESSIONS = type(config.max_sessions) == "number" and config.max_sessions or 20

local data = addon_storage.data
if type(data.sessions) ~= "table" then data.sessions = {} end
if type(data.meta)     ~= "table" then data.meta     = {} end

local known_members = {}
local pid_to_key    = {}
local refid_to_human_key = {}
local cur = nil
local pending_stage = nil

local SKIP_ATTRS = {
  Speed=true, Orientation=true, RPM=true,
  PositionX=true, PositionY=true, PositionZ=true, Ping=true,
}

local function save()
  SavePersistentData()
end

local function uid_now()
  return os.date("%Y%m%d_%H%M%S")
end

local function snapshot_session_attrs()
  if not (session and session.attributes) then return {} end
  local keys = {
    "TrackVariation", "TrackLocation", "TrackId",
    "Stage", "SessionType", "VehicleClassId",
    "MaxPlayers", "GridSize", "ServerName",
    "DamageType", "TireWear", "FuelUsage",
    "RaceWeatherSlots", "WeatherSlots",
    "AllowedViews", "Privacy",
  }
  local out = {}
  for _, k in ipairs(keys) do
    local v = session.attributes[k]
    if v ~= nil then out[k] = v end
  end
  return out
end

local function read_participant_info(p)
  if not p then return nil, nil, nil, nil, nil end
  local a = p.attributes
  local name = p.Name or p.name or (a and (a.Name or a.name))
  local ip = p.IsPlayer
  if ip == nil then ip = p.isPlayer end
  if ip == nil then ip = p.is_player end
  if ip == nil and a then
    ip = a.IsPlayer
    if ip == nil then ip = a.isPlayer end
  end
  local is_player = nil
  if ip ~= nil then is_player = (ip == 1 or ip == true) end

  local vid = p.VehicleId or p.vehicleId or p.vehicle_id
           or (a and (a.VehicleId or a.vehicleId or a.vehicle_id))
  local car  = p.CarName  or p.carname  or p.car
            or (a and (a.CarName  or a.carname))
  local team = p.TeamName or p.teamname or p.team
            or (a and (a.TeamName or a.teamname))
  return name, is_player, vid, car, team
end

local function driver_key(pid, refid)
  if pid ~= nil then
    return "pid_" .. tostring(pid)
  end
  return "ref_" .. tostring(refid)
end

local function new_session(stage, duration_mins)
  cur = {
    uid           = uid_now(),
    stage         = stage,
    duration_mins = duration_mins,
    started_at    = os.date("%Y-%m-%dT%H:%M:%S"),
    attrs         = snapshot_session_attrs(),
    drivers       = {},
    results       = {},
  }
  pid_to_key = {}
  refid_to_human_key = {}
end

local function get_or_create_driver(pid, refid)
  local key = driver_key(pid, refid)
  if pid ~= nil then pid_to_key[pid] = key end
  if not cur.drivers[key] then
    local known = refid and known_members[refid] or nil
    local p = (pid ~= nil and session and session.participants and session.participants[pid]) or nil
    local pname, pis_player, pvid, pcar, pteam = read_participant_info(p)

    local dname = pname or (pis_player == true and known and known.name) or nil
    local dsteam = (pis_player == true and known and known.steamid) or nil

    cur.drivers[key] = {
      refid          = refid,
      participantid  = pid,
      name           = dname,
      steamid        = dsteam,
      is_player      = pis_player,
      vehicle_id     = pvid,
      car            = pcar,
      team           = pteam,
      laps           = {},
      best_lap       = nil,
      best_s1        = nil,
      best_s2        = nil,
      best_s3        = nil,
      started        = false,
      state          = nil,
      final_position = nil,
      has_result     = false,
    }
    if pis_player == true and refid then
      refid_to_human_key[refid] = key
    end
  end
  return cur.drivers[key]
end

local function enrich_driver(d, pid, refid)
  local p = (pid ~= nil and session and session.participants and session.participants[pid]) or nil
  local pname, pis_player, pvid, pcar, pteam = read_participant_info(p)
  if pis_player ~= nil then d.is_player = pis_player end
  if pname and (not d.name or d.is_player == false) then d.name = pname end
  if d.vehicle_id == nil and pvid ~= nil then d.vehicle_id = pvid end
  if pcar  and not d.car  then d.car  = pcar  end
  if pteam and not d.team then d.team = pteam end
  if d.is_player == true and refid and known_members[refid] then
    if not d.name then d.name = known_members[refid].name end
    if d.steamid == nil then d.steamid = known_members[refid].steamid end
    refid_to_human_key[refid] = driver_key(pid, refid)
  end
  if d.is_player == false then
    d.steamid = nil
  end
end

local function finalise_session()
  if not cur then return end

  -- A session nobody took part in (the placeholder made when the addon loads, a lobby that came to nothing) isn't
  -- worth a record: it only fills the list of kept sessions
  if next(cur.drivers) == nil then
    cur = nil
    return
  end

  cur.finished_at = os.date("%Y-%m-%dT%H:%M:%S")

  for _, d in pairs(cur.drivers) do
    enrich_driver(d, d.participantid, d.refid)
    if d.started and not d.has_result then
      d.state = d.state or "DNF"
      table.insert(cur.results, {
        position      = nil,
        refid         = d.refid,
        participantid = d.participantid,
        name          = d.name,
        steamid       = d.steamid,
        is_player     = d.is_player,
        vehicle_id    = d.vehicle_id,
        car           = d.car,
        team          = d.team,
        best_lap_time = d.best_lap and d.best_lap.time or nil,
        best_s1       = d.best_s1,
        best_s2       = d.best_s2,
        best_s3       = d.best_s3,
        total_time    = nil,
        laps_complete = #d.laps,
        state         = d.state,
      })
    end
  end

  table.sort(cur.results, function(a, b)
    local pa = a.position or 9999
    local pb = b.position or 9999
    if pa ~= pb then return pa < pb end
    return (a.laps_complete or 0) > (b.laps_complete or 0)
  end)

  local drivers_arr = {}
  for _, d in pairs(cur.drivers) do
    d.has_result = nil
    table.insert(drivers_arr, d)
  end
  table.sort(drivers_arr, function(a, b)
    local pa = a.final_position or 9999
    local pb = b.final_position or 9999
    if pa ~= pb then return pa < pb end
    return (#a.laps) > (#b.laps)
  end)

  local record = {
    uid           = cur.uid,
    stage         = cur.stage,
    duration_mins = cur.duration_mins,
    started_at    = cur.started_at,
    finished_at   = cur.finished_at,
    attrs         = cur.attrs,
    drivers       = drivers_arr,
    results       = cur.results,
  }

  table.insert(data.sessions, record)
  while #data.sessions > MAX_SESSIONS do
    table.remove(data.sessions, 1)
  end

  data.meta.last_updated = os.date("%Y-%m-%dT%H:%M:%S")
  cur = nil
  save()
end

local function addon_callback(cb, ...)
  if cb == Callback.SessionManagerStateChanged then
    local _, new_state = ...
    if new_state == "Idle" then
      finalise_session()
      known_members = {}
      pid_to_key    = {}
      refid_to_human_key = {}
      pending_stage = nil
    else
      if cur then
        if not cur.stage then cur.stage = new_state end
      else
        pending_stage = new_state
      end
    end
    return
  end

  if not cur then new_session(pending_stage, nil); pending_stage = nil end

  if cb == Callback.SessionAttributesChanged then
    cur.attrs = snapshot_session_attrs()
    return
  end

  if cb == Callback.MemberJoined then
    local refid = ...
    if session and session.members and session.members[refid] then
      local m       = session.members[refid]
      local name    = m.name or m.Name or tostring(refid)
      local steamid = m.steamid or m.SteamID or m.steamId or nil
      known_members[refid] = { name = name, steamid = steamid }
      local hkey = refid_to_human_key[refid]
      if hkey and cur.drivers[hkey] then
        cur.drivers[hkey].name    = cur.drivers[hkey].name or name
        cur.drivers[hkey].steamid = cur.drivers[hkey].steamid or steamid
      end
    end
    save()
    return
  end

  if cb == Callback.MemberLeft then
    local refid = ...
    local hkey = refid_to_human_key[refid]
    local d = hkey and cur.drivers[hkey] or nil
    if d and d.started and not d.state then
      d.state = "DNF"
    end
    return
  end

  if cb == Callback.ParticipantAttributesChanged then
    local pid, names = ...
    if not (session and session.participants and session.participants[pid]) then return end
    local p = session.participants[pid]
    local refid = p.refid
    local has_interesting = false
    if type(names) == "table" then
      for _, aname in ipairs(names) do
        if not SKIP_ATTRS[aname] then has_interesting = true; break end
      end
    end
    if not has_interesting then return end
    local d = get_or_create_driver(pid, refid)
    enrich_driver(d, pid, refid)
    return
  end

  if cb == Callback.EventLogged then
    local event = ...
    if not event then return end
    local ename = event.name
    local attrs = event.attributes or {}

    if ename == "StageChanged" then
      if cur and not cur.stage then
        cur.stage = attrs.PreviousStage
      end
      finalise_session()
      new_session(attrs.NewStage, attrs.Length)
      cur.attrs = snapshot_session_attrs()
      save()
      return
    end

    if ename == "SessionDestroyed" then
      finalise_session()
      return
    end

    if ename == "State" and event.type == "Participant" then
      local refid = event.refid
      local pid   = event.participantid
      if pid ~= nil or refid ~= nil then
        local d = get_or_create_driver(pid, refid)
        enrich_driver(d, pid, refid)
        if not d.has_result then
          d.state = attrs.NewState
        end
      end
      return
    end

    if ename == "Lap" then
      local refid = event.refid
      local pid   = event.participantid
      if pid == nil and not refid then return end

      local d = get_or_create_driver(pid, refid)
      enrich_driver(d, pid, refid)
      if d.vehicle_id == nil and attrs.VehicleId then d.vehicle_id = attrs.VehicleId end
      d.started = true

      local lap_n = attrs.Lap
      local lt    = attrs.LapTime
      local s1    = attrs.Sector1Time
      local s2    = attrs.Sector2Time
      local s3    = attrs.Sector3Time
      local valid = (attrs.CountThisLapTimes == 1)

      table.insert(d.laps, {
        lap      = lap_n,
        time     = lt,
        s1       = s1,
        s2       = s2,
        s3       = s3,
        valid    = valid,
        position = attrs.RacePosition,
      })

      if valid and lt then
        if not d.best_lap or lt < d.best_lap.time then
          d.best_lap = { lap = lap_n, time = lt, s1 = s1, s2 = s2, s3 = s3 }
        end
        if s1 and (not d.best_s1 or s1 < d.best_s1) then d.best_s1 = s1 end
        if s2 and (not d.best_s2 or s2 < d.best_s2) then d.best_s2 = s2 end
        if s3 and (not d.best_s3 or s3 < d.best_s3) then d.best_s3 = s3 end
      end

      save()
      return
    end

    if ename == "Results" then
      local refid = event.refid
      local pid   = event.participantid
      if pid == nil and not refid then return end

      local d = get_or_create_driver(pid, refid)
      enrich_driver(d, pid, refid)
      if d.vehicle_id == nil and attrs.VehicleId then d.vehicle_id = attrs.VehicleId end
      d.has_result     = true
      d.state          = attrs.State or "Finished"
      d.final_position = attrs.RacePosition

      if not d.best_lap and attrs.FastestLapTime then
        d.best_lap = { lap = nil, time = attrs.FastestLapTime }
      end

      table.insert(cur.results, {
        position      = attrs.RacePosition,
        refid         = refid,
        participantid = pid,
        name          = d.name,
        steamid       = d.steamid,
        is_player     = d.is_player,
        vehicle_id    = d.vehicle_id,
        car           = d.car,
        team          = d.team,
        best_lap_time = d.best_lap and d.best_lap.time or nil,
        best_s1       = d.best_s1,
        best_s2       = d.best_s2,
        best_s3       = d.best_s3,
        total_time    = attrs.TotalTime,
        laps_complete = attrs.Lap,
        state         = attrs.State,
      })

      save()
      return
    end

    return
  end
end

data.meta.plugin_version = "1.1-agy"
new_session(nil, nil)
save()

RegisterCallback(addon_callback)
EnableCallback(Callback.SessionManagerStateChanged)
EnableCallback(Callback.SessionAttributesChanged)
EnableCallback(Callback.MemberJoined)
EnableCallback(Callback.MemberLeft)
EnableCallback(Callback.ParticipantAttributesChanged)
EnableCallback(Callback.EventLogged)
