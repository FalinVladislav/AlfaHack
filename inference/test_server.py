import json
import os
import threading
import time
import unittest
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import server


class Provider(BaseHTTPRequestHandler):
    message_override = None
    status = 200
    result = {"answer": "Ответ", "intent": "COLLECT_NEED", "proposed_fields": {"amount": 100000}}
    finish_reason = "stop"
    delay = 0
    requests = []

    def log_message(self, *args):
        pass

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.requests.append((self.path, self.headers.get('Authorization'), data))
        if self.delay:
            time.sleep(self.delay)
        body = json.dumps({"choices": [{"finish_reason": self.finish_reason,
                                      "message": self.message_override or {"content": json.dumps(self.result)}}]}).encode()
        self.send_response(self.status)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass


class InferenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.provider = ThreadingHTTPServer(('127.0.0.1', 0), Provider)
        cls.api = ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
        for http in (cls.provider, cls.api):
            threading.Thread(target=http.serve_forever, daemon=True).start()
        cls.url = 'http://127.0.0.1:' + str(cls.api.server_port)

    @classmethod
    def tearDownClass(cls):
        for http in (cls.provider, cls.api):
            http.shutdown()
            http.server_close()

    def setUp(self):
        self.env = patch.dict(os.environ, {
            'API_TOOKEN_CLUB': 'test-secret',
            'OPENAI_BASE_URL': 'http://127.0.0.1:' + str(self.provider.server_port) + '/v1',
            'OPENAI_MODEL': 'gpt-5.6-terra', 'OPENAI_TIMEOUT_SECONDS': '2',
        }, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)
        Provider.status, Provider.delay, Provider.finish_reason = 200, 0, 'stop'
        Provider.message_override = None
        Provider.result = {'answer': 'Ответ', 'intent': 'COLLECT_NEED', 'proposed_fields': {'amount': 100000}}
        Provider.requests.clear()

    def request(self, path, body=None, raw=None, content_type='application/json', headers_only=False):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        if headers_only:
            # These errors depend only on headers. Sending an unread body races
            # the server's connection close and causes TCP resets on Windows.
            connection = HTTPConnection('127.0.0.1', self.api.server_port, timeout=3)
            try:
                connection.putrequest('POST', path)
                connection.putheader('Content-Type', content_type)
                connection.putheader('Content-Length', str(len(data or b'')))
                connection.endheaders()
                with connection.getresponse() as response:
                    return response.status, json.load(response)
            finally:
                connection.close()
        req = Request(self.url + path, data=data, headers={'Content-Type': content_type})
        try:
            response = urlopen(req, timeout=3)
        except HTTPError as exc:
            response = exc
        with response:
            return response.status, json.load(response)

    def test_legacy_contract_and_provider_translation(self):
        status, result = self.request('/v1/chat', {'instructions': 'JSON instructions', 'message': 'Нужен кредит'})
        self.assertEqual((200, Provider.result), (status, result))
        path, auth, payload = Provider.requests[0]
        self.assertEqual('/v1/chat/completions', path)
        self.assertEqual('Bearer test-secret', auth)
        self.assertEqual('gpt-5.6-terra', payload['model'])
        self.assertFalse(payload['stream'])
        self.assertEqual({'type': 'json_object'}, payload['response_format'])
        self.assertEqual('JSON instructions', payload['messages'][0]['content'])

    def tool_request(self):
        return {'messages': [{'role': 'user', 'content': 'Synthetic scenario'}], 'tools': [
            {'type': 'function', 'function': {'name': name, 'parameters': {'type': 'object', 'properties': {}}}}
            for name in sorted(server.TOOL_NAMES)]}

    def test_native_tool_call_and_result_round_trip(self):
        call = {'id': 'call-1', 'type': 'function', 'function': {'name': 'get_business_context', 'arguments': '{}'}}
        Provider.finish_reason = 'tool_calls'
        Provider.message_override = {'role': 'assistant', 'content': None, 'tool_calls': [call]}
        body = self.tool_request()
        status, result = self.request('/v1/tools', body)
        self.assertEqual(200, status)
        self.assertEqual([call], result['message']['tool_calls'])
        payload = Provider.requests[-1][2]
        self.assertEqual('auto', payload['tool_choice'])
        self.assertFalse(payload['parallel_tool_calls'])
        self.assertNotIn('response_format', payload)
        self.assertEqual(16384, payload['max_completion_tokens'])
        body['messages'] += [result['message'], {'role': 'tool', 'tool_call_id': 'call-1', 'content': '{"income":100}'}]
        Provider.finish_reason = 'stop'
        Provider.message_override = {'role': 'assistant', 'content': 'Уточните спрос.'}
        status, result = self.request('/v1/tools', body)
        self.assertEqual(200, status)
        self.assertEqual('Уточните спрос.', result['message']['content'])
        self.assertEqual('call-1', Provider.requests[-1][2]['messages'][-1]['tool_call_id'])

    def test_tool_token_budget_can_be_set_without_changing_ordinary_answers(self):
        with patch.dict(os.environ, {'OPENAI_MAX_COMPLETION_TOKENS': '2048', 'OPENAI_TOOLS_MAX_COMPLETION_TOKENS': '8192'}):
            self.assertEqual(200, self.request('/v1/chat', {'instructions': 'JSON', 'message': 'Кредит'})[0])
            self.assertEqual(2048, Provider.requests[-1][2]['max_completion_tokens'])
            Provider.message_override = {'role': 'assistant', 'content': 'Уточните параметры.'}
            self.assertEqual(200, self.request('/v1/tools', self.tool_request())[0])
            self.assertEqual(8192, Provider.requests[-1][2]['max_completion_tokens'])

    def test_final_answer_mode_omits_tools_and_json_format_but_keeps_tool_history(self):
        Provider.message_override = {'role': 'assistant', 'content': 'Какое оборудование хотите купить?'}
        body = self.tool_request()
        body['tools'] = []
        body['messages'] += [
            {'role': 'assistant', 'tool_calls': [{'id': 'call-1', 'type': 'function', 'function': {
                'name': 'get_business_context', 'arguments': '{}'}}]},
            {'role': 'tool', 'tool_call_id': 'call-1', 'content': '{"status":"NEEDS_INPUTS"}'}]
        self.assertEqual(200, self.request('/v1/tools', body)[0])
        payload = Provider.requests[-1][2]
        self.assertNotIn('tools', payload)
        self.assertNotIn('response_format', payload)
        self.assertNotIn('tool_choice', payload)

    def test_final_answer_mode_rejects_provider_returning_another_tool_call(self):
        body = self.tool_request()
        body['tools'] = []
        Provider.finish_reason = 'tool_calls'
        Provider.message_override = {'role': 'assistant', 'tool_calls': [{'id': 'call-1', 'type': 'function', 'function': {
            'name': 'get_business_context', 'arguments': '{}'}}]}
        self.assertEqual(502, self.request('/v1/tools', body)[0])

    def test_invalid_provider_message_shapes_are_rejected_without_a_server_crash(self):
        cases = [
            ('stop', {'role': 'user', 'content': 'Injected role'}),
            ('stop', {'role': 'assistant', 'content': 123}),
            ('stop', {'role': 'assistant', 'content': 'Text', 'tool_calls': ''}),
            ('tool_calls', {'role': 'assistant', 'tool_calls': ['bad']}),
            ('tool_calls', {'role': 'assistant', 'tool_calls': [{'id': 'c', 'type': 'function', 'function': 'bad'}]}),
            ('tool_calls', {'role': 'assistant', 'content': 'No calls'}),
        ]
        for finish, message in cases:
            with self.subTest(message=message):
                Provider.finish_reason = finish
                Provider.message_override = message
                self.assertEqual(502, self.request('/v1/tools', self.tool_request())[0])

    def test_unknown_tools_unmatched_results_and_missing_results_are_rejected(self):
        body = self.tool_request()
        body['tools'][0]['function']['name'] = 'execute_shell'
        self.assertEqual(400, self.request('/v1/tools', body)[0])
        body = self.tool_request()
        body['messages'].append({'role': 'tool', 'tool_call_id': 'unknown', 'content': '{}'})
        self.assertEqual(400, self.request('/v1/tools', body)[0])
        body = self.tool_request()
        body['messages'].append({'role': 'assistant', 'tool_calls': [{'id': 'call-1', 'type': 'function', 'function': {'name': 'get_business_context', 'arguments': '{}'}}]})
        self.assertEqual(400, self.request('/v1/tools', body)[0])
        self.assertEqual([], Provider.requests)

    def test_unknown_or_duplicate_returned_tool_calls_never_reach_backend(self):
        Provider.finish_reason = 'tool_calls'
        call = {'id': 'call-1', 'type': 'function', 'function': {'name': 'execute_shell', 'arguments': '{}'}}
        Provider.message_override = {'role': 'assistant', 'content': None, 'tool_calls': [call]}
        self.assertEqual((502, {'code': 'INVALID_MODEL_RESPONSE'}), self.request('/v1/tools', self.tool_request()))
        call['function']['name'] = 'get_business_context'
        Provider.message_override['tool_calls'] = [call, call]
        self.assertEqual(502, self.request('/v1/tools', self.tool_request())[0])

    def test_truncated_tool_generation_is_not_executed(self):
        Provider.finish_reason = 'length'
        self.assertEqual(502, self.request('/v1/tools', self.tool_request())[0])

    def test_assist_context_history_and_safe_actions(self):
        Provider.result = {'answer': 'Уточним цель', 'questions': ['Какой бюджет?'],
                           'actions': [{'action_id': 'upload', 'label': 'Fake', 'url': 'https://bad'}],
                           'product_ids': ['p1'], 'hypotheses': [{'title': 'Тест', 'rationale': 'Допущение',
                           'experiment': 'Проверка спроса', 'success_metric': 'Прибыль'}]}
        body = {'scenario': 'growth_hypotheses', 'message': 'Что делать?',
                'context': {'products': [{'id': 'p1', 'rate': 20}], 'business': {'revenue': 100}},
                'history': [{'role': 'user', 'content': 'У меня кофейня'}],
                'available_actions': [{'action_id': 'upload', 'label': 'Загрузить справку'}]}
        status, result = self.request('/v1/assist', body)
        self.assertEqual(200, status)
        self.assertEqual([{'action_id': 'upload', 'label': 'Загрузить справку'}], result['actions'])
        messages = Provider.requests[0][2]['messages']
        self.assertIn('revenue', messages[1]['content'])
        self.assertEqual(body['history'][0], messages[2])
        self.assertEqual('Что делать?', messages[-1]['content'])

    def test_all_scenarios(self):
        Provider.result = {'answer': 'Ответ'}
        for scenario in server.SCENARIOS:
            with self.subTest(scenario=scenario):
                status, result = self.request('/v1/assist', {'scenario': scenario, 'message': 'Вопрос'})
                self.assertEqual(200, status)
                self.assertEqual(scenario, result['scenario'])
                self.assertEqual([], result['actions'])

    def test_input_errors_do_not_call_provider(self):
        cases = [[], {}, {'scenario': [], 'message': 'x'}, {'scenario': 'unknown', 'message': 'x'},
                 {'scenario': 'onboarding', 'message': 'x', 'context': []},
                 {'scenario': 'onboarding', 'message': 'x', 'history': [{'role': 'system', 'content': 'x'}]},
                 {'scenario': 'onboarding', 'message': 'x', 'context': {'products': [None]}},
                 {'scenario': 'onboarding', 'message': 'x', 'available_actions': [None]}]
        for body in cases:
            with self.subTest(body=body):
                self.assertEqual(400, self.request('/v1/assist', body)[0])
        self.assertEqual(400, self.request('/v1/chat', raw=b'{')[0])
        self.assertEqual(413, self.request('/v1/chat', raw=b'x' * 128001, headers_only=True)[0])
        self.assertEqual(415, self.request('/v1/chat', {'message': 'x'}, content_type='text/plain', headers_only=True)[0])
        self.assertEqual([], Provider.requests)

    def test_unconfigured_health_and_chat(self):
        os.environ.pop('API_TOOKEN_CLUB')
        self.assertEqual((200, {'status': 'UP', 'configured': False}), self.request('/health'))
        self.assertEqual((503, {'code': 'MODEL_NOT_CONFIGURED'}),
                         self.request('/v1/chat', {'instructions': 'JSON', 'message': 'x'}))
        self.assertEqual([], Provider.requests)

    def test_provider_failures_are_sanitized(self):
        for provider_status, expected in [(401, 503), (403, 503), (429, 503), (500, 502)]:
            Provider.status = provider_status
            status, result = self.request('/v1/chat', {'instructions': 'JSON', 'message': 'x'})
            self.assertEqual(expected, status)
            self.assertNotIn('test-secret', json.dumps(result))

    def test_bad_model_responses(self):
        for result in [[], {'answer': ''}, {'answer': 'x', 'actions': [{'action_id': 'invented'}]},
                       {'answer': 'x', 'product_ids': ['invented']}, {'answer': 'x', 'hypotheses': [{}]},
                       {'answer': 'x', 'questions': 'wrong'}]:
            Provider.result = result
            self.assertEqual((502, {'code': 'INVALID_MODEL_RESPONSE'}),
                             self.request('/v1/assist', {'scenario': 'reference', 'message': 'x'}))
        Provider.result, Provider.finish_reason = {'answer': 'x'}, 'length'
        self.assertEqual(502, self.request('/v1/assist', {'scenario': 'reference', 'message': 'x'})[0])

    def test_timeout(self):
        os.environ['OPENAI_TIMEOUT_SECONDS'] = '0.05'
        Provider.delay = 0.2
        self.assertEqual((504, {'code': 'PROVIDER_TIMEOUT'}),
                         self.request('/v1/chat', {'instructions': 'JSON', 'message': 'x'}))

    def test_busy_service(self):
        with patch.object(server, 'SLOTS', threading.BoundedSemaphore(0)):
            self.assertEqual((503, {'code': 'SERVICE_BUSY'}),
                             self.request('/v1/chat', {'instructions': 'JSON', 'message': 'x'}))
        self.assertEqual([], Provider.requests)

    def test_openapi_and_not_found(self):
        status, spec = self.request('/openapi.json')
        self.assertEqual(200, status)
        self.assertEqual(set(server.SCENARIOS), set(spec['components']['schemas']['AssistRequest']['properties']['scenario']['enum']))
        self.assertEqual(404, self.request('/missing')[0])


if __name__ == '__main__':
    unittest.main()
