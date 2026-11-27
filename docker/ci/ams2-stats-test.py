#!/usr/bin/env python3
"""Tests the ams2_stats Lua addon against a stand-in for the game server's Lua API (Lua 5.3).

  pip install lupa && python3 docker/ci/ams2-stats-test.py
"""
import unittest
from pathlib import Path

from lupa.lua53 import LuaRuntime

ADDON = Path(__file__).resolve().parents[2] / 'src/main/resources/lua/ams2_stats/ams2_stats.lua'

PRELUDE = r'''
Callback = {
  SessionManagerStateChanged = 1,
  SessionAttributesChanged = 2,
  MemberJoined = 3,
  MemberLeft = 4,
  ParticipantAttributesChanged = 5,
  EventLogged = 6,
}
handler = nil
function RegisterCallback(f) handler = f end
function EnableCallback() end
function SavePersistentData() end
session = { attributes = { TrackId = 100, VehicleClassId = -1027000403 }, members = {}, participants = {} }
'''


class Ams2StatsTest(unittest.TestCase):
    def setUp(self):
        self.lua = LuaRuntime(unpack_returned_tuples=True)
        self.lua.execute(PRELUDE)
        storage = self.lua.table_from({'config': self.lua.table_from({'max_sessions': 20}), 'data': self.lua.table_from({})})
        self.fn = self.lua.eval(f'function(...) {ADDON.read_text(encoding="utf-8")} end')
        self.fn(storage)
        self.storage = storage
        self.handler = self.lua.globals().handler
        self.cb = self.lua.globals().Callback
        self.session = self.lua.globals().session

    def test_ai_car_sharing_host_refid_never_gets_host_name_even_when_attributes_arrive_late(self):
        # Host McRod joins with refid = 1
        self.session.members[1] = self.lua.table_from({'name': 'McRod', 'steamid': '76561198000000001'})
        self.handler(self.cb.MemberJoined, 1)

        # Stage changes to Race1
        self.handler(self.cb.EventLogged, self.lua.table_from({
            'name': 'StageChanged',
            'attributes': self.lua.table_from({'NewStage': 'Race1', 'Length': 5}),
        }))

        # Human pid=0 and AI pid=1 both share refid=1, and AI participant attributes aren't populated yet on first State event
        self.session.participants[0] = self.lua.table_from({
            'refid': 1,
            'attributes': self.lua.table_from({'Name': 'McRod', 'IsPlayer': 1, 'VehicleId': -403158996}),
        })
        self.session.participants[1] = self.lua.table_from({'refid': 1, 'attributes': self.lua.table_from({})})

        self.handler(self.cb.EventLogged, self.lua.table_from({
            'name': 'State', 'type': 'Participant', 'refid': 1, 'participantid': 0,
            'attributes': self.lua.table_from({'NewState': 'Racing'}),
        }))
        self.handler(self.cb.EventLogged, self.lua.table_from({
            'name': 'State', 'type': 'Participant', 'refid': 1, 'participantid': 1,
            'attributes': self.lua.table_from({'NewState': 'Racing'}),
        }))

        # Now AI participant attributes arrive
        self.session.participants[1].attributes = self.lua.table_from({
            'Name': 'Alejandro Fernandez  (AI)', 'IsPlayer': 0, 'VehicleId': -403158996,
        })
        self.handler(self.cb.ParticipantAttributesChanged, 1, self.lua.table_from(['Name', 'IsPlayer']))

        # Both finish a lap and get results
        for pid, lap_time in [(0, 82000), (1, 83000)]:
            self.handler(self.cb.EventLogged, self.lua.table_from({
                'name': 'Lap', 'refid': 1, 'participantid': pid,
                'attributes': self.lua.table_from({
                    'Lap': 1, 'LapTime': lap_time, 'Sector1Time': 27000, 'Sector2Time': 28000, 'Sector3Time': lap_time - 55000,
                    'CountThisLapTimes': 1, 'VehicleId': -403158996,
                }),
            }))
            self.handler(self.cb.EventLogged, self.lua.table_from({
                'name': 'Results', 'refid': 1, 'participantid': pid,
                'attributes': self.lua.table_from({
                    'RacePosition': pid + 1, 'FastestLapTime': lap_time, 'TotalTime': lap_time, 'State': 'Finished',
                }),
            }))

        # Session ends -> Idle finalises Race1
        self.handler(self.cb.SessionManagerStateChanged, 'Race1', 'Idle')

        sessions = self.storage['data']['sessions']
        self.assertEqual(1, len(sessions))
        results = list(dict(sessions[1]['results']).values())
        by_pid = {r['participantid']: r for r in results}
        self.assertEqual('McRod', by_pid[0]['name'])
        self.assertTrue(by_pid[0]['is_player'])
        self.assertEqual('Alejandro Fernandez  (AI)', by_pid[1]['name'])
        self.assertFalse(by_pid[1]['is_player'])


if __name__ == '__main__':
    unittest.main()
