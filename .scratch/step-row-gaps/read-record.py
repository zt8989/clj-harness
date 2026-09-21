import json, sys

p = sys.argv[1]
for line in open(p, encoding='utf-8'):
    o = json.loads(line)
    t = o.get('type')
    pay = o.get('payload')
    if t == 'message':
        m = pay.get('message', pay)
        c = m.get('content')
        print('MESSAGE', m.get('id'), m.get('role'), json.dumps(c, ensure_ascii=False)[:160])
    else:
        print('event', json.dumps(pay, ensure_ascii=False)[:140])