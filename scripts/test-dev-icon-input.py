#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('dev_icon', Path(__file__).with_name('generate-dev-launcher-icons.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DevIconInputTest(unittest.TestCase):
    def parse(self, content):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'foreground.xml'
            path.write_bytes(content)
            return module.read_foreground(path)

    def test_valid_repository_vector(self):
        self.assertEqual('vector', module.read_foreground(module.MAIN / 'drawable/ic_launcher_foreground.xml').tag)

    def test_dtd_and_entity_declarations_are_rejected(self):
        for content in (b'<!DOCTYPE vector [<!ENTITY x SYSTEM "file:///nonexistent">]><vector>&x;</vector>',
                        b'<!ENTITY x "value"><vector/>'):
            with self.assertRaises(ValueError): self.parse(content)

    def test_input_is_bounded_and_utf8_only(self):
        for content in (b' ' * (128 * 1024 + 1), '<vector/>'.encode('utf-16')):
            with self.assertRaises(ValueError): self.parse(content)

    def test_different_document_type_is_rejected(self):
        with self.assertRaises(ValueError): self.parse(b'<svg/>')


if __name__ == '__main__': unittest.main()
