#!/usr/bin/env python
import sys, urllib, urllib2
try:
    from com.xhaus.jyson import JysonCodec as json
except ImportError:
    # From Python
    import json 


def transform(a_json, rtype):
    alla_json = []
    resten_json = {}
    link = None
    id001 = None

    top_title = None

    for f in a_json['fields']:
        for k, v in f.items():
            if k == '001':
                link = "/%s/%s" % (rtype, v)
                id001 = v
            if k in ['100', '700']:
                should_add = True
                sug_json = {}
                sug_json['100'] = {}
                sug_json['100']['ind1'] = v['ind1']
                for sf in v['subfields']:
                    for sk, sv in sf.items():
                        if sk == '0' and rtype == 'bib':
                            should_add = False
                        if sk in ['a','b','c','d']:
                            sug_json['100'][sk] = sv
                if should_add:
                    if k == '100':
                        sug_json['identifier'] = "/%s/%s/%s" % (w_name, suggest_source, id001)
                    else:
                        name = "%s/%s" % (id001, '_'.join(sug_json['100'].values()[1:]).replace(",","").replace(" ", "_").replace(".","").replace("[","").replace("]",""))

                        #print "values", w_name, suggest_source, "name:", name
                        sug_json['identifier'] = "/%s/%s/%s" % (w_name, suggest_source, name)
                    alla_json.append(sug_json)
            elif k in get_fields(rtype):
                f_list = resten_json.get(k, [])
                d = {}
                for sf in v['subfields']:
                    for sk, sv in sf.items():
                        d[sk] = sv
                f_list.append(d)
                if k == '245':
                    titleparts = {}
                    for sf in v['subfields']:
                        for sk, sv in sf.items():
                            if sk in ['a', 'b']:
                                titleparts[sk] = sv.strip('/ ')
                                
                    #print "titleparts", titleparts
                    top_title = "%s %s" % (titleparts['a'], titleparts.get('b', ''))
                    
                resten_json[k] = f_list

    #print "sug_json", sug_json
    #print "resten_json", resten_json
    #print "alla_json", alla_json

    resten_json['records'] = 1

    # get_records for auth-records
    if rtype == 'auth':
        if len(alla_json) > 0 and alla_json[0].get('100', None):
            #f_100 = ' '.join(alla_json[0]['100'].values()[1:])
            f_100 = alla_json[0]['100']
            resten_json = get_records(f_100, resten_json)
    # single top-title for bibrecords
    elif top_title: 
        resten_json['top_titles'] = {"%s%s" % ("http://libris.kb.se", link) : top_title.strip()}

    # More for resten
    if link:
        resten_json['link'] = link
    resten_json['authorized'] = True if rtype == 'auth' else False
        
    # cleanup
    try:
        resten_json.pop('245')
    except:
        1

    # append resten on alla
    #print "resten", resten_json
    ny_alla = []
    for my_json in alla_json:
        ny_alla.append(dict(my_json.items() + resten_json.items()))

    #print "alla", ny_alla
    return ny_alla
 
def get_fields(rtype):
    if rtype == 'auth':
        return ['400', '500', '678', '680', '856']
    return ['245', '678']

def get_records(f_100, sug_json):
    if bibwhelk:
        query_100 = []
        query_700 = []
        for k, v in f_100.items():
            if k in ['a','b','c','d']:
                query_100.append("fields.100.subfields.%s:\"%s\"" % (k,v))
                query_700.append("fields.700.subfields.%s:\"%s\"" % (k,v))
        q_100 = ' AND '.join(query_100)
        q_700 = ' AND '.join(query_700)
        q_swe = "fields.008:swe"

        q_all = '((%s) OR (%s)) AND %s' % (q_100, q_700, q_swe)

        response = bibwhelk.search(q_all) 
        #print "Count: ", response.getNumberOfHits()
        sug_json['records'] = response.getNumberOfHits()

        top_3 = {}
        for document in response.hits[:3]:
            jdoc = json.loads(document.getDataAsString())
            f_001, title = top_title_tuple(jdoc['fields'])
            top_3[f_001] = title
        
        top_missing = 5 - len(top_3)
        q_all = '(%s) OR (%s)' % (q_100, q_700)

        response = bibwhelk.search(q_all) 
        #print "Count: ", response.getNumberOfHits()
        sug_json['records'] = response.getNumberOfHits()
        for document in response.hits[:top_missing]:
            jdoc = json.loads(document.getDataAsString())
            f_001, title = top_title_tuple(jdoc['fields'])
            top_3[f_001] = title

        #print "top_3", top_3
        only_top_3 = {}
        for k, v in top_3.items()[:3]:
            only_top_3[k] = v
        sug_json['top_titles'] = only_top_3
        
    return sug_json

def top_title_tuple(fields):
    f_001 = ''
    f_245_a = ''
    f_245_b = ''
    f_245_n = ''
    for field in fields:
        for k, v in field.items():
            if k == '001':
                f_001 = v
            elif k == '245':
                for sf in v['subfields']:
                    for sk, sv in sf.items():
                        if sk == 'a':
                            f_245_a = sv
                        elif sk == 'b':
                            f_245_b = sv
                        elif sk == 'n':
                            f_245_n = sv
    if f_001 and f_245_a:
        title = f_245_a.strip(':;/.') + f_245_b.strip(':;/') + ' ' + f_245_n.strip(':;/ ')
    return (f_001, title.strip())

                          
def _get_records(f_100, sug_json):
    try:
        url = 'http://libris.kb.se/xsearch'
        values = {'query' : 'forf:(%s) spr:swe' % f_100, 'format' : 'json'}

        data = urllib.urlencode(values)
        #print "XSEARCH URL: %s?%s" % (url, data)
        reply = urllib2.urlopen(url + "?" + data)

        response = reply.read().decode('utf-8')
        #print "got response", response, type(response)

        xresult = json.loads(response)['xsearch']

        sug_json['records'] = xresult['records']
        top_3 = xresult['list'][:3]
        top_titles = {}
        for p in top_3:
            top_titles[p['identifier']] = unicode(p['title'])
        sug_json['top_titles'] = top_titles

    except:
        print "exception in get_records"
        0
    return sug_json

def record_type(leader):
    if list(leader)[6] == 'z':
        return "auth"
    return "bib"


_in_console = False
try:
    data = document.getDataAsString()
    ctype = document.getContentType()
except:
    print "Exception in python setup ... Likely, nothing will run now."
    ctype = "application/json"
    data = sys.stdin.read()
    suggestwhelk = None
    bibwhelk = None
    _in_console = True


#print "console mode", _in_console

if ctype == 'application/json':
    in_json = json.loads(data)
    rtype = record_type(in_json['leader'])
    suggest_source = rtype 
    if (rtype == 'bib'):
        suggest_source = 'name'

    w_name = suggestwhelk.prefix if suggestwhelk else "test"

    #identifier = "/%s/%s/%s" % (w_name, suggest_source, document.identifier.toString().split("/")[-1])
    sug_jsons = transform(in_json, rtype)
    for sug_json in sug_jsons:
        identifier = sug_json['identifier'] 
        r = json.dumps(sug_json)
        #print "r", r

        if suggestwhelk:
            mydoc = suggestwhelk.createDocument().withIdentifier(identifier).withData(r).withContentType("application/json")

            #print "Sparar dokument i whelken daaraa"
            uri = suggestwhelk.add(mydoc)

if _in_console:
    print r
