import json
from pathlib import Path
from collections import Counter

d = json.loads(open('graphify-out/.graphify_detect.json', 'r', encoding='utf-8').read())
scan_root = d.get('scan_root', '').rstrip('/').rstrip('\\')

all_files = []
for cat, items in d['files'].items():
    all_files.extend(items)

root_norm = scan_root.replace('\\', '/')
graphify_prefix = root_norm + '/graphify-out/'

# Top 5 first-level subdirs
first_level = Counter()
for f in all_files:
    f_norm = f.replace('\\', '/')
    if f_norm.startswith(graphify_prefix):
        continue
    if not f_norm.startswith(root_norm):
        continue
    rel = f_norm[len(root_norm):].lstrip('/')
    parts = rel.split('/')
    if len(parts) >= 1 and parts[0]:
        first_level[parts[0]] += 1
    else:
        first_level['(root)'] += 1

print("=== Top 5 first-level subdirectories ===")
for dname, count in first_level.most_common(5):
    print(f'  {dname}/: {count} files')

# Check if all files are in (root)
non_root_count = sum(c for d, c in first_level.items() if d != '(root)')
if non_root_count == 0:
    print("\nAll files are directly in the root with no subdirectories.")
    print("Suggest: --no-cluster to skip the expensive clustering step.")

# Show deeper structure for app/src
src_dirs = Counter()
for f in all_files:
    f_norm = f.replace('\\', '/')
    if f_norm.startswith(graphify_prefix):
        continue
    if not f_norm.startswith(root_norm):
        continue
    rel = f_norm[len(root_norm):].lstrip('/')
    parts = rel.split('/')
    if len(parts) >= 3 and parts[0] == 'app' and parts[1] == 'src':
        sub = '/'.join(parts[:3])
        src_dirs[sub] += 1

print("\n=== Inside app/src/ ===")
# Filter out .cxx artifacts
real_dirs = Counter({k: v for k, v in src_dirs.items() if '.cxx' not in k})
for dname, count in real_dirs.most_common(10):
    print(f'  {dname}/: {count} files')

# Even deeper - app/src/main/
main_dirs = Counter()
for f in all_files:
    f_norm = f.replace('\\', '/')
    if f_norm.startswith(graphify_prefix):
        continue
    if not f_norm.startswith(root_norm):
        continue
    rel = f_norm[len(root_norm):].lstrip('/')
    parts = rel.split('/')
    if len(parts) >= 4 and parts[:3] == ['app', 'src', 'main']:
        sub = '/'.join(parts[:4])
        main_dirs[sub] += 1

print("\n=== Inside app/src/main/ ===")
for dname, count in main_dirs.most_common(10):
    print(f'  {dname}/: {count} files')
