# The real game, started the way the Modrinth app does, with the same Forge and Embeddium, in a throwaway folder (run-real/) that never touches a profile.
# The jar must be built with -Psmoke so LodChecks is in it:  gradlew build -Psmoke -x test
#   python tools/run_real_client.py survey seeds=1,2,3            biome maps of seeds, in the log
#   python tools/run_real_client.py pregen seed=4 cx=0 cz=0 seconds=420     make chunks in a spiral; run it again to go on
#   python tools/run_real_client.py scenes [rd=10]                walks through run-real/scenes.txt, screenshots in run-real/screenshots
# Add mods=<prefix,prefix> to also copy those mods from the Powers profile, or allmods to take every mod in it.
import glob
import json
import os
import shutil
import subprocess
import sys

M = os.path.join(os.environ['APPDATA'], 'ModrinthApp')
META = os.path.join(M, 'meta')
VERSION = '1.20.1-47.4.28'
JAVA = r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe'
PROFILE_MODS = os.path.join(M, 'profiles', 'Powers', 'mods')
NEEDED = ['embeddium-']
RUN = os.path.abspath('run-real')
TIMEOUT = 570


def arg(name, default=None):
    for a in sys.argv[1:]:
        if a.startswith(name + '='):
            return a[len(name) + 1:]
    return default


def allowed(rules):
    ok = not rules
    for r in rules or []:
        name = (r.get('os') or {}).get('name')
        if name in (None, 'windows') and 'features' not in r:
            ok = r['action'] == 'allow'
    return ok


def library_path(lib):
    art = (lib.get('downloads') or {}).get('artifact')
    if art and art.get('path'):
        return art['path']
    group, name, ver = lib['name'].split(':')[:3]
    return f"{group.replace('.', '/')}/{name}/{ver}/{name}-{ver}.jar"


def main():
    mode = sys.argv[1]
    d = json.load(open(os.path.join(META, 'versions', VERSION, VERSION + '.json'), encoding='utf-8'))
    cp, seen = [], set()
    for lib in d['libraries']:
        if lib.get('include_in_classpath') is False or not allowed(lib.get('rules')) or 'natives' in lib['name'].split(':')[-1] and 'windows' not in lib['name']:
            continue
        path = os.path.join(META, 'libraries', library_path(lib))
        if os.path.exists(path) and path not in seen:
            seen.add(path)
            cp.append(path)
    cp.append(os.path.join(META, 'versions', VERSION, VERSION + '.jar'))

    os.makedirs(os.path.join(RUN, 'mods'), exist_ok=True)
    os.makedirs(os.path.join(RUN, 'screenshots'), exist_ok=True)
    for old in glob.glob(os.path.join(RUN, 'mods', '*.jar')):
        os.remove(old)
    needed = list(NEEDED)
    extra = arg('mods')
    if extra:
        needed += extra.split(',')
    every = 'allmods' in sys.argv
    if 'noembeddium' in sys.argv:
        needed = [n for n in needed if n != 'embeddium-']
    for name in os.listdir(PROFILE_MODS):
        if name.endswith('.jar') and (every or any(name.startswith(n) for n in needed)) and 'charlod' not in name:
            shutil.copy(os.path.join(PROFILE_MODS, name), os.path.join(RUN, 'mods', name))
    built = sorted(glob.glob('build/libs/charlod-*.jar'), key=os.path.getmtime)[-1]
    shutil.copy(built, os.path.join(RUN, 'mods', 'charlod-smoke.jar'))
    print('mods:', len(os.listdir(os.path.join(RUN, 'mods'))), 'jar', built)

    natives = os.path.join(META, 'natives', VERSION)
    values = {'natives_directory': natives, 'launcher_name': 'charlod-test', 'launcher_version': '1', 'classpath': os.pathsep.join(cp), 'classpath_separator': os.pathsep,
              'library_directory': os.path.join(META, 'libraries'), 'version_name': VERSION, 'auth_player_name': 'Dev', 'game_directory': RUN, 'assets_root': os.path.join(META, 'assets'),
              'assets_index_name': d['assets'], 'auth_uuid': '00000000000000000000000000000abc', 'auth_access_token': '0', 'clientid': '0', 'auth_xuid': '0', 'user_type': 'legacy', 'version_type': 'release'}

    def fill(text):
        for key, value in values.items():
            text = text.replace('${' + key + '}', value)
        return text

    def flat(args):
        out = []
        for a in args:
            if isinstance(a, str):
                out.append(fill(a))
            elif allowed(a.get('rules')):
                v = a['value']
                out.extend(fill(x) for x in ([v] if isinstance(v, str) else v))
        return out

    props = ['-Dcharlod.check=' + mode]
    for key in ('seed', 'seeds', 'cx', 'cz', 'seconds', 'rd', 'then'):
        v = arg(key)
        if v is not None:
            props.append(f'-Dcharlod.{key}={v}')
    heap = arg('heap', '2500M')
    cmd = [JAVA, '-Xmx' + heap] + props + flat(d['arguments']['jvm']) + [d['mainClass']] + flat(d['arguments']['game']) + ['--width', arg('w', '1600'), '--height', arg('h', '900')]
    with open(os.path.join(RUN, 'launch.log'), 'w', encoding='utf-8', errors='replace') as log:
        try:
            result = subprocess.run(cmd, cwd=RUN, stdout=log, stderr=subprocess.STDOUT, timeout=int(arg('timeout', str(TIMEOUT))))
            print('exit', result.returncode)
        except subprocess.TimeoutExpired:
            print('timed out')
    print('screenshots:', len(glob.glob(os.path.join(RUN, 'screenshots', '*.png'))))
    return 0


if __name__ == '__main__':
    sys.exit(main())
