"""Packaging checks using generated, untrusted identities only; needs JDK 17 and Git's OpenSSL.
Set TEST_ISCC to also compile and install isolated test installers (a unique AppId, no real app).
"""
import base64
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import uuid

HERE = Path(__file__).resolve().parent
JAVA_HOME = Path(os.environ['JAVA_HOME'])
OPENSSL = Path(os.environ.get('TEST_OPENSSL', r'C:\Program Files\Git\usr\bin\openssl.exe'))
POWERSHELL = os.environ.get('TEST_POWERSHELL', 'powershell.exe')


def run(*args, expected=0, env=None):
    result = subprocess.run([str(a) for a in args], capture_output=True, timeout=120, env=env)
    if result.returncode != expected:
        raise AssertionError(f'{Path(args[0]).name} exited {result.returncode}: '
                             + result.stdout.decode(errors='replace') + result.stderr.decode(errors='replace'))
    return result


def make_pair(folder, curve='prime256v1'):
    folder.mkdir()
    key, cert = folder / 'test.key', folder / 'test.crt'
    run(OPENSSL, 'req', '-new', '-x509', '-newkey', 'ec', '-pkeyopt', f'ec_paramgen_curve:{curve}',
        '-nodes', '-keyout', key, '-out', cert, '-days', '1', '-subj', '/CN=Untrusted packaging test')
    run(OPENSSL, 'pkcs8', '-topk8', '-nocrypt', '-in', key, '-outform', 'DER', '-out', folder / 'identity.pk8')
    run(OPENSSL, 'crl2pkcs7', '-nocrl', '-certfile', cert, '-outform', 'DER', '-out', folder / 'certificate.p7b')


class MfiPackaging(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='chairplay-mfi-test-')
        cls.root = Path(cls.temp.name)
        cls.pair = cls.root / 'pair'
        make_pair(cls.pair)
        run(JAVA_HOME / 'bin/javac.exe', '-d', cls.root, HERE / 'ValidateMfi.java')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def validate(self, directory, expected=0):
        return run(JAVA_HOME / 'bin/java.exe', '-cp', self.root, 'ValidateMfi', directory, expected=expected)

    def test_accepts_matching_pair(self):
        self.validate(self.pair)

    def test_rejects_mismatched_key(self):
        other = self.root / 'other'
        make_pair(other)
        shutil.copyfile(self.pair / 'certificate.p7b', other / 'certificate.p7b')
        self.validate(other, 1)

    def test_rejects_other_curve(self):
        other = self.root / 'curve'
        make_pair(other, 'secp384r1')
        self.validate(other, 1)

    def test_rejects_multiple_certificates(self):
        other = self.root / 'chain'
        shutil.copytree(self.pair, other)
        run(OPENSSL, 'crl2pkcs7', '-nocrl', '-certfile', self.pair / 'test.crt',
            '-certfile', self.pair / 'test.crt', '-outform', 'DER', '-out', other / 'certificate.p7b')
        self.validate(other, 1)

    def test_rejects_missing_malformed_empty_and_oversized_files(self):
        for name in ['identity.pk8', 'certificate.p7b']:
            for contents in [None, b'', b'not a credential', b'x' * 16385]:
                with self.subTest(name=name, size=None if contents is None else len(contents)):
                    directory = self.root / str(uuid.uuid4())
                    shutil.copytree(self.pair, directory)
                    target = directory / name
                    if contents is None:
                        target.unlink()
                    else:
                        target.write_bytes(contents)
                    self.validate(directory, 1)

    def test_prepares_secrets_and_rejects_bad_or_missing_inputs(self):
        good = dict(os.environ, MFI_PRIVATE_KEY_B64=base64.b64encode((self.pair / 'identity.pk8').read_bytes()).decode(),
                    MFI_CERTIFICATE_B64=base64.b64encode((self.pair / 'certificate.p7b').read_bytes()).decode())
        output = self.root / 'decoded'
        command = [POWERSHELL, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', HERE / 'PrepareMfi.ps1', '-Destination']
        run(*command, output, env=good)
        self.validate(output)
        run(*command, output, env=good, expected=1)
        for name in ['MFI_PRIVATE_KEY_B64', 'MFI_CERTIFICATE_B64']:
            for bad in ['', 'invalid-base64!', base64.b64encode(b'x' * 16385).decode()]:
                directory = self.root / str(uuid.uuid4())
                run(*command, directory, env=dict(good, **{name: bad}), expected=1)
                self.assertFalse(directory.exists())

    @unittest.skipUnless(os.environ.get('TEST_ISCC'), 'TEST_ISCC not set')
    def test_installer_modes_and_preservation(self):
        image = self.root / 'image'
        image.mkdir()
        (image / 'ChairPlay.exe').write_bytes(b'Not a runnable app; silent setup never launches it.')
        script = (HERE / 'ChairPlay.iss').read_text(encoding='utf-8')
        identity = str(uuid.uuid4())
        script = script.replace('25CBBAE6-C3C2-48CB-AB46-25D3386168D8', identity)
        script = script.replace('AppName=ChairPlay', f'AppName=ChairPlay packaging test {identity}')
        script = script.replace('UninstallDisplayName=ChairPlay', f'UninstallDisplayName=ChairPlay packaging test {identity}')
        script = script.replace('{autoprograms}\\ChairPlay', f'{{autoprograms}}\\ChairPlay packaging test {identity}')
        script = script.replace('{autodesktop}\\ChairPlay', f'{{autodesktop}}\\ChairPlay packaging test {identity}')
        script = script.replace('SetupIconFile=..\\chairplay.ico', f'SetupIconFile={HERE.parent / "chairplay.ico"}')
        source = self.root / 'test.iss'
        source.write_text(script, encoding='utf-8')
        for bundled in [False, True]:
            output = self.root / ('bundled' if bundled else 'plain')
            args = [os.environ['TEST_ISCC'], '/Q', '/DAppVersion=0.0.0', f'/DSourceDir={image}',
                    f'/DOutputDir={output}', '/DAppData=offline-mfi']
            if bundled:
                args += [f'/DMfiSourceDir={self.pair}']
            run(*args, source)
            for existing in [[], ['identity.pk8'], ['certificate.p7b'], ['identity.pk8', 'certificate.p7b']]:
                with self.subTest(bundled=bundled, existing=existing):
                    install = self.root / str(uuid.uuid4())
                    target = install / 'offline-mfi'
                    target.mkdir(parents=True)
                    for name in existing:
                        (target / name).write_bytes(b'Existing user file')
                    try:
                        log = install / 'setup.log'
                        try:
                            run(output / 'ChairPlay-0.0.0-setup.exe', '/VERYSILENT', '/SUPPRESSMSGBOXES',
                                '/SP-', '/NORESTART', f'/DIR={install}', f'/LOG={log}')
                        except AssertionError:
                            if log.exists():
                                print(log.read_text(encoding='utf-8', errors='replace'))
                            raise
                        expected = existing or (['identity.pk8', 'certificate.p7b'] if bundled else [])
                        self.assertEqual(sorted(p.name for p in target.iterdir()), sorted(expected))
                        for name in expected:
                            value = b'Existing user file' if existing else (self.pair / name).read_bytes()
                            self.assertEqual((target / name).read_bytes(), value)
                    finally:
                        uninstall = install / 'unins000.exe'
                        if uninstall.exists():
                            run(uninstall, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART')
                    self.assertEqual(sorted(p.name for p in target.iterdir()), sorted(expected))


if __name__ == '__main__':
    unittest.main()
