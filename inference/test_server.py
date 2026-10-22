import io
import json
import os
import unittest
from unittest.mock import patch

import server


class InferenceTest(unittest.TestCase):
    @patch.dict(os.environ, {"OLLAMA_MODEL": "test-model", "OLLAMA_URL": "http://provider:11434"}, clear=True)
    @patch("server.urlopen")
    def test_provider_details_are_translated_inside_inference_service(self, call):
        expected = {"intent": "COLLECT_NEED", "answer": "Review", "proposed_fields": {"amount": 100000}}
        call.return_value = io.BytesIO(json.dumps({"message": {"content": json.dumps(expected)}}).encode())
        self.assertEqual(expected, server.complete("Instructions", "Message"))
        request = call.call_args.args[0]
        self.assertEqual("http://provider:11434/api/chat", request.full_url)
        payload = json.loads(request.data)
        self.assertEqual("test-model", payload["model"])
        self.assertFalse(payload["stream"])
        self.assertEqual("Instructions", payload["messages"][0]["content"])

    @patch.dict(os.environ, {}, clear=True)
    @patch("server.urlopen")
    def test_unconfigured_service_never_calls_provider(self, call):
        with self.assertRaises(RuntimeError):
            server.complete("Instructions", "Message")
        call.assert_not_called()

    @patch.dict(os.environ, {"OLLAMA_MODEL": "test-model"}, clear=True)
    @patch("server.urlopen")
    def test_non_object_model_response_is_rejected(self, call):
        call.return_value = io.BytesIO(b'{"message":{"content":"[]"}}')
        with self.assertRaises(ValueError):
            server.complete("Instructions", "Message")


if __name__ == "__main__":
    unittest.main()
