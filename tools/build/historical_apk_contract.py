"""Finite reviewed pre-WorkManager release identities; never a version-based fallback."""
from enum import Enum


class ApkContract(Enum):
    PRE_WORKMANAGER = 'pre-workmanager'


# Reviewed immutable source commit -> (tree, exact published tags). Adding a new
# historical identity requires source and effective APK surface review, not a cutoff.
LEGACY_SOURCES = {
    '296e030c68e79235397d7e4d082aaf463ce95f7e': (
        '30353f7174afd47b5e62d676e434bcffa1aafefb', frozenset({'v0.1.0-rc.1'})),
    'd9b7298e5e68fb599f8d2e3f83ac9f2ed3c65cbb': (
        '5244f167e8bbc238ce8b758d8448eb7a8185df55', frozenset({'v0.1.0-rc.2', 'v0.1.0'})),
    '7447edae1a5ec918c9f97ca59b62e5bdf0595168': (
        '0e846d37c98459740eb0ff951c99cdfedade1e9b', frozenset({'v0.2.0-rc.1'})),
    'be3aeb2f9c5626fcec681896ee000ba5bb46c424': (
        '05d5313c4755efaa24a87d5feda0ab970da7c700', frozenset({'v0.2.0-rc.2'})),
    'a509eb8f0339e743e83b22591304910138940fcd': (
        'b34063eeca2c8b76cfb61bca8c7b4d3939accab0', frozenset({'v0.2.0-rc.3', 'v0.2.0'})),
}
LEGACY_MANIFEST_SHA256 = '0b3d46464500145bb714692db7d18d106173d6ee0a03e11c0c37526ea724d624'


def contract_for(tag, source):
    """Caller must independently resolve tag/source/tree before selecting policy."""
    reviewed = LEGACY_SOURCES.get(source['sha'])
    if reviewed is not None and source == {'sha': source['sha'], 'tree': reviewed[0]} and tag in reviewed[1]:
        return ApkContract.PRE_WORKMANAGER
    return None


def is_legacy(contract):
    if contract is None:
        return False
    if contract is not ApkContract.PRE_WORKMANAGER:
        raise ValueError('Unknown historical APK contract')
    return True


def check_binding(expected, binding, contract):
    """An optional legacy contract cannot authorize self-declared or different inputs."""
    legacy = is_legacy(contract)
    if binding is not None and binding != {key: expected[key] for key in ('tag', 'source')}:
        raise ValueError('Delivered tag/source binding differs')
    if legacy and (binding is None or contract_for(binding['tag'], binding['source']) is not contract):
        raise ValueError('Historical APK contract requires independently bound source')
