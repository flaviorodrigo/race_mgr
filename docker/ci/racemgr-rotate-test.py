#!/usr/bin/env python3
"""Tests the racemgr_rotate Lua addon against a stand-in for the game server's Lua API (Lua 5.3, like the server).

  pip install lupa && python3 docker/ci/racemgr-rotate-test.py

The stand-in LibRotate has the same interface as the server's lib_rotate (new, verify_setup, merge_setup). To run
against the real library, set LIB_ROTATE_LUA to a copy of lib_rotate.lua and provide its globals (not done here).
"""
import os
import sys
import tempfile
import unittest
from pathlib import Path

from lupa.lua53 import LuaRuntime

ADDON = Path(__file__).resolve().parents[2] / 'src/main/resources/lua/racemgr_rotate/racemgr_rotate.lua'

PRELUDE = r'''
-- The game server's Lua has no load()
load = nil
loadstring = nil
Callback = { ServerStateChanged = 1, EventLogged = 2, Tick = 3 }
handler = nil
function RegisterCallback(f) handler = f end
function EnableCallback() end
applied = {}
function SetNextSessionAttributes(a) applied[#applied + 1] = a end
saves = 0
function SavePersistentData() saves = saves + 1 end
session = { next_attributes = { ServerControlsSetup = 1 } }
now = 1000
os.time = function() return now end

local known = { RaceLength = true, TrackId = true, QualifyLength = true, ManualPitStops = true, DamageType = true }
LibRotate = {}
LibRotate.__index = LibRotate
function LibRotate.new(default)
  for k in pairs(default) do if not known[k] then error("invalid default " .. k) end end
  return setmetatable({ default = default }, LibRotate)
end
function LibRotate:verify_setup(setup)
  for k in pairs(setup) do if not known[k] then print("unknown attribute " .. k); return false end end
  return true
end
function LibRotate:merge_setup(setup)
  local merged = {}
  for k, v in pairs(self.default) do merged[k] = v end
  for k, v in pairs(setup or {}) do merged[k] = v end
  return merged
end

function load_addon(path, config, data)
  local chunk = assert(loadfile(path))
  chunk({ config = config, data = data })
end
function fire(...) handler(...) end
function event(previous, new)
  handler(Callback.EventLogged, { type = "Session", name = "StateChanged", attributes = { PreviousState = previous, NewState = new } })
end
'''


def rotation_file(version, rounds, default='RaceLength = 15, ManualPitStops = "Manual"', reset=None):
    items = ', '.join('{ TrackId = "%s" }' % r for r in rounds)
    extra = '' if reset is None else 'reset = %d, ' % reset
    return 'return { version = %d, %sdefault = { %s }, rotation = { %s } }\n' % (version, extra, default, items)


class RotateTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        os.makedirs(os.path.join(self.dir.name, 'lua_config'))
        os.makedirs(os.path.join(self.dir.name, 'lua_output'))
        self.cwd = os.getcwd()
        os.chdir(self.dir.name)
        self.lua = LuaRuntime(unpack_returned_tuples=True)
        self.lua.execute(PRELUDE)
        self.g = self.lua.globals()

    def tearDown(self):
        os.chdir(self.cwd)
        self.dir.cleanup()

    # -- helpers
    def start(self, rounds=('A', 'B', 'C'), index=0, with_file=None):
        if with_file:
            self.write(with_file)
        self.lua.execute('''
            storage_config = { persist_index = true, default = { RaceLength = 5 }, rotation = {%s} }
            storage_data = { index = %d }
        ''' % (', '.join('{ TrackId = "%s" }' % r for r in rounds), index))
        self.lua.execute('load_addon("%s", storage_config, storage_data)' % ADDON)
        self.lua.execute('fire(Callback.ServerStateChanged, "Starting", "Running")')

    def write(self, text):
        Path('lua_config/racemgr_rotation.lua').write_text(text)

    def tick(self, seconds=10):
        self.lua.execute('now = now + %d; fire(Callback.Tick)' % seconds)

    def applied(self):
        return [self.g.applied[i + 1] for i in range(len(self.g.applied))]

    def tracks(self):
        return [a['TrackId'] for a in self.applied()]

    def status(self):
        text = Path('lua_config/racemgr_rotation_status.lua').read_text()
        return self.lua.execute(text)

    # -- tests
    def test_without_a_file_it_rotates_like_sms_rotate(self):
        self.start()
        self.assertEqual(self.tracks(), ['A'])
        self.lua.execute('event("Lobby", "Loading")')
        self.lua.execute('event("Lobby", "Loading")')
        self.lua.execute('event("Lobby", "Loading")')
        self.assertEqual(self.tracks(), ['A', 'B', 'C', 'A'])

    def test_a_session_destroyed_in_the_lobby_advances(self):
        self.start()
        self.lua.execute('event("Idle", "Lobby")')
        self.lua.execute('handler(Callback.EventLogged, { type = "Session", name = "SessionDestroyed" })')
        self.assertEqual(self.tracks(), ['A', 'B'])

    def test_start_uses_the_rotation_file_and_continues_from_the_saved_position(self):
        self.start(index=1, with_file=rotation_file(7, ['X', 'Y', 'Z']))
        self.assertEqual(self.tracks(), ['Y'])  # position 1 -> next is round 2 of the file's rotation
        self.assertEqual(self.applied()[0]['RaceLength'], 15)
        self.assertEqual(self.status()['version'], 7)

    def test_a_new_rotation_is_picked_up_without_restart_and_the_position_is_kept(self):
        self.start()
        self.lua.execute('event("Lobby", "Loading")')  # now queued: round 2 (B)
        self.assertEqual(self.tracks(), ['A', 'B'])
        self.write(rotation_file(1, ['A', 'B', 'C', 'D']))
        self.tick()
        # the queued round (position 2) is set again, from the new rotation
        self.assertEqual(self.tracks(), ['A', 'B', 'B'])
        self.assertEqual(self.applied()[-1]['RaceLength'], 15)
        self.assertEqual(self.status()['rounds'], 4)
        self.assertEqual(self.status()['note'], 'applied')
        # and the added track comes up in its turn
        self.lua.execute('event("Lobby", "Loading")')
        self.lua.execute('event("Lobby", "Loading")')
        self.assertEqual(self.tracks()[-2:], ['C', 'D'])

    def test_nothing_happens_between_checks(self):
        self.start()
        self.tick(0)  # the first tick after loading checks at once
        self.write(rotation_file(1, ['A', 'B']))
        self.tick(4)
        self.assertEqual(self.tracks(), ['A'])
        self.tick(6)
        self.assertEqual(self.tracks(), ['A', 'A'])

    def test_an_unchanged_file_is_not_applied_again(self):
        self.start()
        self.write(rotation_file(1, ['A', 'B']))
        self.tick()
        count = len(self.applied())
        self.tick()
        self.tick()
        self.assertEqual(len(self.applied()), count)

    def test_a_broken_file_is_refused_once_and_the_previous_rotation_stays(self):
        self.start()
        self.write('return { this is not lua')
        self.tick()
        self.assertTrue(self.status()['note'].startswith('rejected'))
        self.tick()
        self.assertEqual(self.tracks(), ['A'])
        self.lua.execute('event("Lobby", "Loading")')
        self.assertEqual(self.tracks(), ['A', 'B'])

    def test_a_rotation_with_an_unknown_setting_is_refused(self):
        self.start()
        self.write(rotation_file(1, ['A'], default='NotASetting = 1'))
        self.tick()
        self.assertTrue(self.status()['note'].startswith('rejected'))
        self.assertEqual(self.tracks(), ['A'])

    def test_a_shorter_rotation_keeps_the_position_inside_it(self):
        self.start()
        self.lua.execute('event("Lobby", "Loading")')
        self.lua.execute('event("Lobby", "Loading")')  # queued round 3
        self.write(rotation_file(1, ['A', 'B']))
        self.tick()
        self.assertEqual(self.tracks()[-1], 'B')
        self.assertEqual(self.status()['index'], 2)

    def test_the_file_is_a_table_only_it_cannot_run_code(self):
        self.start()
        marker = Path('marker.txt')
        self.write('os.execute("touch marker.txt") return { version = 1, default = {}, rotation = { { TrackId = "A" } } }')
        self.tick()
        self.assertFalse(marker.exists())
        self.assertTrue(self.status()['note'].startswith('rejected'))

    def test_reads_exactly_what_race_manager_writes(self):
        # The text below is what RotationLuaFileTest checks Race Manager writes (keys in brackets, quoted strings,
        # decimal escapes for anything that isn't plain ASCII)
        self.start()
        self.write('''return {
  version = 1795567161000,
  default = { ["RaceLength"] = 15, ["ManualPitStops"] = "Manual" },
  rotation = { { ["TrackId"] = "Laguna_Seca_2020" }, { ["TrackId"] = "Brasilia\\010\\195\\169", ["RaceLength"] = 20 } }
}
''')
        self.tick()
        self.assertEqual(self.status()['note'], 'applied')
        self.assertEqual(self.status()['version'], 1795567161000)
        self.assertEqual(self.status()['rounds'], 2)
        self.lua.execute('event("Lobby", "Loading")')
        self.assertEqual(self.applied()[-1]['TrackId'], 'Brasilia\n\u00e9')
        self.assertEqual(self.applied()[-1]['RaceLength'], 20)

    def test_a_rotation_arriving_after_a_start_without_one_queues_its_first_round(self):
        self.start(rounds=())  # no rotation in the addon's config and no file yet
        self.assertEqual(self.tracks(), [])
        self.write(rotation_file(1, ['A', 'B']))
        self.tick()
        self.assertEqual(self.tracks(), ['A'])
        self.assertEqual(self.status()['index'], 1)
        self.lua.execute('event("Lobby", "Loading")')
        self.assertEqual(self.tracks(), ['A', 'B'])

    def test_works_when_the_server_only_lets_addons_reach_lua_output(self):
        self.lua.execute("""
            local real_open = io.open
            io.open = function(path, mode)
              if path:sub(1, 11) == "lua_config/" then return nil, "denied" end
              return real_open(path, mode)
            end
        """)
        Path('lua_output/racemgr_rotation.lua').write_text(rotation_file(3, ['X', 'Y']))
        self.start(rounds=())
        self.assertEqual(self.tracks(), ['X'])
        status = self.lua.execute(Path('lua_output/racemgr_rotation_status.lua').read_text())
        self.assertEqual(status['version'], 3)
        self.assertFalse(Path('lua_config/racemgr_rotation_status.lua').exists())

    def test_without_the_io_library_it_still_rotates_its_own_config(self):
        self.lua.execute('io = nil')
        self.start()
        self.assertEqual(self.tracks(), ['A'])

    def test_the_parser_reads_numbers_text_and_nesting_as_race_manager_writes_them(self):
        self.start()
        self.write('''return {
  -- a comment
  version = 42,
  default = { ["RaceLength"] = 15, ["TrackId"] = -559709709, ["QualifyLength"] = 87.5, ["DamageType"] = "A\\\\B\\"C", ["ManualPitStops"] = true, },
  rotation = { { ["TrackId"] = "Monza\\0952020" } }
}''')
        self.tick()
        self.assertEqual(self.status()['note'], 'applied')
        a = self.applied()[-1]
        self.assertEqual(a['TrackId'], 'Monza_2020')
        self.assertEqual(a['RaceLength'], 15)
        self.assertEqual(self.lua.eval('math.type')(a['RaceLength']), 'integer')
        self.assertEqual(a['QualifyLength'], 87.5)
        self.assertEqual(a['DamageType'], 'A\\B"C')
        self.assertIs(a['ManualPitStops'], True)

    def test_the_parser_refuses_anything_but_a_table(self):
        self.start()
        for bad in ['return { version = 1, default = {}, rotation = { os.execute("x") } }',
                    'return { version = 1 } extra',
                    'print("x") return {}',
                    'return { version = 1, default = { ["A"] = "unfinished }, rotation = {} }']:
            self.write(bad)
            self.tick()
            self.assertTrue(self.status()['note'].startswith('rejected'), bad)
        self.assertEqual(self.tracks(), ['A'])

    def test_an_error_inside_the_addon_is_logged_with_its_name(self):
        self.start()
        self.lua.execute('SetNextSessionAttributes = function() error("boom") end')
        out = []
        self.lua.globals().print = lambda *a: out.append(' '.join(str(x) for x in a))
        self.lua.execute('event("Lobby", "Loading")')
        self.assertTrue(any(line.startswith('RacemgrRotate: error:') and 'boom' in line for line in out), out)

    def test_a_reset_request_starts_the_rotation_again_from_round_1_without_a_restart_and_only_once(self):
        self.start()
        self.lua.execute('event("Lobby", "Loading")')
        self.lua.execute('event("Lobby", "Loading")')  # queued: C
        self.write(rotation_file(1, ['A', 'B', 'C'], reset=500))
        self.tick()
        self.assertEqual(self.tracks()[-1], 'A')
        self.assertEqual(self.status()['index'], 1)
        self.assertEqual(self.status()['reset'], 500)
        self.assertEqual(self.status()['note'], 'applied, from round 1')
        self.lua.execute('event("Lobby", "Loading")')
        self.assertEqual(self.tracks()[-1], 'B')
        # The same request again (a later rotation change that still carries it) doesn't start over
        self.write(rotation_file(2, ['A', 'B', 'C'], reset=500))
        self.tick()
        self.assertEqual(self.tracks()[-1], 'B')
        self.assertEqual(self.status()['index'], 2)

    def test_a_reset_request_present_at_start_up_starts_from_round_1(self):
        self.start(index=2, with_file=rotation_file(1, ['X', 'Y', 'Z'], reset=900))
        self.assertEqual(self.tracks(), ['X'])

    def test_the_position_is_saved_when_advancing(self):
        self.start()
        before = self.g.saves
        self.lua.execute('event("Lobby", "Loading")')
        self.assertGreater(self.g.saves, before)

    def test_all_humans_retiring_repeats_the_current_round(self):
        self.start()  # round 1 ('A') queued, index = 1
        self.lua.execute('event("Lobby", "Loading")')  # advances to round 2 ('B'), index = 2
        self.assertEqual(self.tracks()[-1], 'B')
        self.lua.execute('''
            session = { participants = { [0] = { IsPlayer = 1 } } }
            handler(Callback.EventLogged, { type = "Session", name = "StageChanged", attributes = { NewStage = "Race1" } })
            handler(Callback.EventLogged, { type = "Participant", name = "State", participantid = 0, attributes = { NewState = "Retired" } })
            handler(Callback.EventLogged, { type = "Session", name = "StateChanged", attributes = { PreviousState = "Racing", NewState = "PostRace" } })
        ''')
        self.assertEqual(self.tracks()[-1], 'A')
        self.assertEqual(self.status()['index'], 1)
        self.assertEqual(self.status()['note'], 'repeated, all retired')


if __name__ == '__main__':
    sys.exit(unittest.main(verbosity=2))
