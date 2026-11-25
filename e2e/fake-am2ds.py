"""A stand-in for the AMS2 dedicated server HTTP API, for the end-to-end tests: usage `fake-am2ds.py <port>`."""
import base64
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs, unquote_plus

members = [
    {"refid": 7, "steamid": "76561198000000001", "name": "Ayrton S", "attributes": {"Ping": 42}},
    {"refid": 9, "steamid": "76561198000000002", "name": "Alain P", "attributes": {"Ping": 77}},
    {"refid": 11, "steamid": "76561198000000003", "name": "Nigel M", "attributes": {"Ping": 120}},
]
calls = []

# What a real dedicated server answers for these (saved from one): the session settings, option lists, flags, car classes
LISTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'test', 'resources', 'am2ds_lists')
REAL_LISTS = {
    '/api/list/attributes/session': 'attributes_session.json',
    '/api/list/enums': 'enums.json',
    '/api/list/enums/privatesession': 'enums_privatesession.json',
    '/api/list/enums/scheduledfcy': 'enums_scheduledfcy.json',
    '/api/list/flags/session': 'flags_session.json',
    '/api/list/vehicle_classes': 'vehicle_classes.json',
}


def car(i, name, pos, lap, sector, s1, s2, s3, last, best, player=1, state="Racing"):
    return {"id": i, "attributes": {"RefId": 7 + i, "Name": name, "IsPlayer": player, "RacePosition": pos, "CurrentLap": lap, "CurrentSector": sector,
                                    "Sector1Time": s1, "Sector2Time": s2, "Sector3Time": s3, "LastLapTime": last, "FastestLapTime": best, "State": state, "VehicleId": 55}}


PARTICIPANTS = [
    car(2, "Alain P", 2, 4, 2, 39950, 12400, 31700, 104400, 103900),
    car(1, "Ayrton S", 1, 4, 3, 39800, 32400, 9000, 103500, 103500),
    car(3, "Nigel M", 3, 3, 1, 5000, 32800, 31900, 105200, 104900, state="EnteringPits"),
    car(4, "Bot 4", 4, 4, 1, 3000, 33100, 32000, 106000, 105100, player=0),
]
LOG = [
    {"index": 0, "time": 1795456000, "name": "SessionCreated", "attributes": {}},
    {"index": 1, "time": 1795456010, "name": "PlayerJoined", "refid": 7, "attributes": {"Name": "Ayrton S"}},
    {"index": 2, "time": 1795456100, "name": "StageChanged", "attributes": {"PreviousStage": "Qualifying", "NewStage": "Race1", "Length": 10}},
    {"index": 3, "time": 1795456200, "name": "ServerChat", "attributes": {"Message": "<b>hello</b> & welcome"}},
]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, body):
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlparse(self.path)
        raw_query = url.query
        query = parse_qs(raw_query, keep_blank_values=True)
        needs_admin = url.path in ('/api/session/kick', '/api/session/send_chat', '/api/session/set_next_attributes', '/api/session/advance')
        expected = os.environ.get('FAKE_ADMIN_AUTH')
        if needs_admin and expected and self.headers.get('Authorization') != 'Basic ' + base64.b64encode(expected.encode()).decode():
            calls.append({"call": "rejected", "path": url.path})
            self.send_response(401)
            self.send_header('WWW-Authenticate', 'Basic realm="ams2"')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if url.path == '/api/session/status':
            self.reply({"result": "ok", "response": {
                "state": "Running",
                "attributes": {"SessionStage": "Race1", "TrackId": 1234, "MaxPlayers": 12, "RaceLength": 10},
                "members": members, "participants": PARTICIPANTS}})
        elif url.path == '/api/log/range':
            self.reply({"result": "ok", "response": {"first": 0, "count": len(LOG), "now": 1795456740, "events": LOG}})
        elif url.path == '/api/session/kick':
            calls.append({"call": "kick", "raw_query": raw_query})
            refid = int(query['refid'][0])
            members[:] = [m for m in members if m['refid'] != refid]
            self.reply({"result": "ok"})
        elif url.path == '/api/session/send_chat':
            calls.append({"call": "chat", "raw_query": raw_query, "message": query['message'][0], "params": sorted(query.keys())})
            self.reply({"result": "ok"})
        elif url.path == '/_calls':
            self.reply(calls)
        elif url.path == '/api/session/set_next_attributes':
            calls.append({"call": "set_next", "raw_query": raw_query})
            self.reply({"result": "ok"})
        elif url.path == '/api/session/advance':
            calls.append({"call": "advance"})
            self.reply({"result": "ok"})
        elif url.path == '/api/list/tracks':
            self.reply({"result": "ok", "response": {"list": [{"id": 1534602052, "name": "Brands_Hatch_GP", "gridsize": 32}, {"id": 1234, "name": "Test_Track", "gridsize": 20}]}})
        elif url.path == '/api/list/vehicles':
            self.reply({"result": "ok", "response": {"list": [{"id": 55, "name": "Test GT3", "class": "GT3"}]}})
        elif url.path in REAL_LISTS:
            self.reply(json.load(open(os.path.join(LISTS, REAL_LISTS[url.path]))))
        elif url.path == '/api/list/vehicle_classes_fake':
            self.reply({"result": "ok", "response": {"list": [{"id": 1, "name": "F-V12"}, {"id": 2, "name": "GT3"}]}})
        elif url.path == '/api/list/enums/weather':
            self.reply({"result": "ok", "response": {"list": [{"name": "Clear"}, {"name": "LightRain"}, {"name": "Overcast"}]}})
        elif url.path.startswith('/api/list/'):
            self.reply({"result": "ok", "response": {"list": [{"id": 1234, "name": "Test_Track"}]}})
        else:
            self.reply({"result": "error"})


HTTPServer(('127.0.0.1', int(sys.argv[1])), Handler).serve_forever()
