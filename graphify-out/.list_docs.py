import json
d = json.loads(open('graphify-out/.graphify_detect.json', 'r', encoding='utf-8').read())
prefix = "H:\\Work\\OmniDocs\\"
print('=== DOCUMENTS ===')
for f in d['files'].get('document', []):
    f_clean = f.replace(prefix, '').replace('\\', '/')
    print(f'  {f_clean}')
print()
print('=== PAPERS ===')
for f in d['files'].get('paper', []):
    f_clean = f.replace(prefix, '').replace('\\', '/')
    print(f'  {f_clean}')
print()
print('=== IMAGES ===')
for f in d['files'].get('image', []):
    f_clean = f.replace(prefix, '').replace('\\', '/')
    print(f'  {f_clean}')
