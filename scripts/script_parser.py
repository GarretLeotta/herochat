

outprefix = '../logs/formatted/'
log_fname = '../logs/test-log.log'
pattern = 'date time thread level actor message'

actor_patterns = {
    'akka://herochat/user/hcController/bigboss': 'bigboss0.actor.log',
    'akka://herochat/user/bigbosst1': 'bigboss1.actor.log',
    'akka://herochat/user/bigbosst2': 'bigboss2.actor.log',
    'akka://herochat/user/bigbosst3': 'bigboss3.actor.log',
    'akka://herochat/user/bigbosst4': 'bigboss4.actor.log',
}

outfiles = {}
for pattern, filename in actor_patterns.items():
    outfiles[pattern] = open(outprefix+filename, 'w')


log_file = open(log_fname, 'r')

line_no = 0

while True:
    #we'll de-split the message after we separate out the prefix stuff
    line = log_file.readline()
    line_no += 1
    if not line:
        break
    line = line.strip().split(' ')
    try:
        dt, tm, thread, lvl, actor, = line[:5]
        msg = ' '.join(line[5:])

        for prefix, file in outfiles.items():
            if actor.startswith(prefix):
                file.write(actor+' '+msg+'\n')

    except ValueError:
        print(line_no, line)



log_file.close()

for outfile in outfiles.values():
    outfile.close()
