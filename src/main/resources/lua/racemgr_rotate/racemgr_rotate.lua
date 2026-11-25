--[[

racemgr_rotate: the rotation addon of sms_rotate, with a rotation that Race Manager can replace while the server runs.

It does what sms_rotate does: applies the first setup when the server starts, and the next setup of the rotation each
time a session leaves the lobby (or is destroyed while still in it). On top of that, every few seconds it looks at
racemgr_rotation.lua, which Race Manager writes into lua_output and lua_config when a rotation is activated (the
server may only let addons reach lua_output). When that file changed, the
new rotation replaces the old one without restarting the server, the position in the rotation is kept, and the next
session is set again from the new rotation at once. A rotation with a mistake in it is refused and the previous one stays.
After every change it writes racemgr_rotation_status.lua into both folders, so Race Manager can see what the addon is doing.

Use it instead of sms_rotate (not together with it): in server.cfg, put "racemgr_rotate" in luaApiAddons where
"sms_rotate" was. The rotation file format is a Lua table:

  return { version = 1795567161000, default = { RaceLength = 15, ... }, rotation = { { TrackId = "Monza_2020" }, ... } }

The default and the rotation entries are the same setups sms_rotate uses (see lib_rotate.lua), names or numbers.

--]]

local addon_storage = ...
local config = addon_storage.config
config.persist_index = config.persist_index or false
if type( config.default ) ~= "table" then config.default = {} end
if type( config.rotation ) ~= "table" then config.rotation = {} end

local addon_data = addon_storage.data
if not config.persist_index or not addon_data.index then addon_data.index = 0 end

-- Race Manager writes the rotation into both folders: the server may limit addon file access to lua_output
local FOLDERS = { "lua_output/", "lua_config/" }
local ROTATION_NAME = "racemgr_rotation.lua"
local STATUS_NAME = "racemgr_rotation_status.lua"
local CHECK_EVERY_SECONDS = 5
local TAG = "RacemgrRotate: "

-- The rotation in use: from the addon's own config until Race Manager's file replaces it
local rotation = config.rotation
local lib_rotation = LibRotate.new( config.default )
local rotation_ok = true
local loaded_version = 0
local last_text = nil
local last_check = 0
local last_note = "started"


-- The rotation file from the first folder the addon can read
local function read_rotation_file()
	if not io then
		return nil
	end
	for _, folder in ipairs( FOLDERS ) do
		local file = io.open( folder .. ROTATION_NAME, "r" )
		if file then
			local text = file:read( "a" )
			file:close()
			if text and text ~= "" then
				return text
			end
		end
	end
	return nil
end


-- Says once, at start-up, which folders the addon can use: the first thing to look at when no rotation arrives
local function report_file_access()
	if not io then
		print( TAG .. "The Lua io library is not available to addons on this server: Race Manager can't hand rotations over" )
		return
	end
	for _, folder in ipairs( FOLDERS ) do
		local file = io.open( folder .. ROTATION_NAME, "r" )
		print( TAG .. folder .. ROTATION_NAME .. ( file and " can be read" or " can't be read (missing, or no access)" ) )
		if file then
			file:close()
		end
	end
end


-- Tells Race Manager what the addon is doing. A file that can't be written is not worth stopping for.
local function write_status( note )
	last_note = note or last_note
	if not io then
		return
	end
	local text = string.format( "return { version = %s, index = %s, rounds = %s, ok = %s, note = %q }\n",
		tostring( loaded_version ), tostring( addon_data.index or 0 ), tostring( #rotation ), tostring( rotation_ok ), last_note )
	for _, folder in ipairs( FOLDERS ) do
		local file = io.open( folder .. STATUS_NAME, "w" )
		if file then
			file:write( text )
			file:close()
		end
	end
end


-- Checks a rotation with the library the way sms_rotate does. Returns true when it can be used.
local function check_rotation( lib, setups )
	local ok = true
	if #setups == 0 then
		print( TAG .. "No rotation defined, will only apply the defaults" )
		return false
	end

	if session and session.next_attributes and session.next_attributes.ServerControlsSetup == 0 then
		print( TAG .. "Using scripted setup rotation while the server is not configured to control the game's setup. Make sure to set \"controlGameSetup\" in the server config." )
		ok = false
	end

	for index, setup in ipairs( setups ) do
		if not lib:verify_setup( setup ) then
			print( TAG .. "Setup at index " .. index .. " contains errors!" )
			ok = false
		end
	end
	return ok
end


-- Sets the next session from the rotation at the current position, without moving it
local function apply_current_setup()
	if not rotation_ok or #rotation == 0 then
		return
	end

	local index = addon_data.index
	if not index or index < 1 then
		return
	end
	if index > #rotation then
		index = #rotation
		addon_data.index = index
	end

	local attributes = lib_rotation:merge_setup( rotation[ index ] )
	if attributes then
		SetNextSessionAttributes( attributes )
	end
end


local advance_next_setup


-- Looks for a new rotation from Race Manager. force: read it even if it looks the same as last time.
-- apply: set the next session from it now (not needed at start-up, when the first setup is applied right after)
local function reload_rotation( force, apply )
	local text = read_rotation_file()
	if not text or ( text == last_text and not force ) then
		return
	end
	last_text = text

	local chunk, problem = load( text, "racemgr_rotation", "t", {} )
	if not chunk then
		print( TAG .. "The rotation file can't be read: " .. tostring( problem ) )
		write_status( "rejected: the rotation file can't be read" )
		return
	end

	local ran, data = pcall( chunk )
	if not ran or type( data ) ~= "table" or type( data.default ) ~= "table" or type( data.rotation ) ~= "table" then
		print( TAG .. "The rotation file has no default and rotation" )
		write_status( "rejected: the rotation file has no default and rotation" )
		return
	end

	local made, lib = pcall( LibRotate.new, data.default )
	if not made then
		print( TAG .. "The default setup is not valid: " .. tostring( lib ) )
		write_status( "rejected: the default setup is not valid" )
		return
	end

	if not check_rotation( lib, data.rotation ) then
		print( TAG .. "The new rotation contains errors, keeping the previous one" )
		write_status( "rejected: the rotation contains errors" )
		return
	end

	lib_rotation = lib
	rotation = data.rotation
	rotation_ok = true
	loaded_version = tonumber( data.version ) or 0
	if addon_data.index and addon_data.index > #rotation then
		addon_data.index = #rotation
	end
	if apply then
		if not addon_data.index or addon_data.index < 1 then
			-- Nothing was queued yet (the server started without a rotation): queue the first setup now
			advance_next_setup()
		else
			apply_current_setup()
		end
	end
	SavePersistentData()
	write_status( "applied" )
	print( TAG .. "Loaded a rotation of " .. #rotation .. " setups" )
end


-- The main "rotate to next setup" function
advance_next_setup = function()
	if not rotation_ok then
		return
	end

	-- Save state before advancing (as on startup we tick this once too)
	SavePersistentData()

	local setup
	if #rotation > 0 then
		if not addon_data.index then addon_data.index = 0 end
		if addon_data.index < 0 then addon_data.index = 0 end
		addon_data.index = addon_data.index + 1
		if addon_data.index > #rotation then addon_data.index = 1 end
		setup = rotation[ addon_data.index ]
	end

	local attributes = lib_rotation:merge_setup( setup )
	if attributes then
		SetNextSessionAttributes( attributes )
	end
	write_status()
end


-- Used to track state changes to decide when to advance the setup
local last_session_state = "None"


local function addon_callback( callback, ... )

	-- Set the first setup when the server starts, from Race Manager's rotation when there is one
	if callback == Callback.ServerStateChanged then
		local oldState, newState = ...
		if ( oldState == "Starting" ) and ( newState == "Running" ) then
			report_file_access()
			reload_rotation( true, false )
			rotation_ok = check_rotation( lib_rotation, rotation )
			advance_next_setup()
			write_status()
		end
	end

	-- A new rotation from Race Manager, looked for every few seconds
	if callback == Callback.Tick then
		local now = os.time()
		if now - last_check >= CHECK_EVERY_SECONDS then
			last_check = now
			reload_rotation( false, true )
		end
	end

	-- Lobby->Loading: we started the current track, advance to the next.
	-- Session destroyed while in the lobby: quit while in the lobby, also advance to the next.
	if callback == Callback.EventLogged then
		local event = ...
		if ( event.type == "Session" ) and ( event.name == "StateChanged" ) then
			if ( event.attributes.PreviousState == "Lobby" ) and ( event.attributes.NewState == "Loading" ) then
				advance_next_setup()
			end
			last_session_state = event.attributes.NewState
		elseif ( event.type == "Session" ) and ( event.name == "SessionDestroyed" ) then
			if last_session_state == "Lobby" then
				advance_next_setup()
			end
			last_session_state = "None"
		end
	end
end


RegisterCallback( addon_callback )
EnableCallback( Callback.ServerStateChanged )
EnableCallback( Callback.EventLogged )
EnableCallback( Callback.Tick )

-- EOF --
