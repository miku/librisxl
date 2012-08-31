package se.kb.libris.whelks.plugin.external

import se.kb.libris.whelks.*
import se.kb.libris.whelks.plugin.BasicFormatConverter

import groovy.json.*

class AutoSuggestFormatConverter extends BasicFormatConverter {

    def suggestwhelk
    def bibwhelk
    def w_name
    def suggest_source

    String id = "autoSuggestFormatConverter"

    AutoSuggestFormatConverter(Map req) {
        this.suggestwhelk = req["suggestwhelk"]
        this.bibwhelk = req["bibwhelk"]
    }

    @Override
    public Document convert(Document document) {
        def data = document.getDataAsString()
        def ctype = document.getContentType()

        if (ctype == "application/json") {
            def in_json = new JsonSlurper().parseText(data)
            def rtype = record_type(in_json["leader"])
            //println "rtype: $rtype"
            suggest_source = (rtype == "bib" ? "name" : rtype)
            w_name = (suggestwhelk ? suggestwhelk.prefix : "test");
            def sug_jsons = transform(in_json, rtype)
            for (sug_json in sug_jsons) {
                def identifier = sug_json["identifier"];
                def r = new JsonBuilder(sug_json).toString()
                //print "r", r
                if (suggestwhelk) {
                    def mydoc = suggestwhelk.createDocument().withIdentifier(identifier).withData(r).withContentType("application/json");
                    def uri = suggestwhelk.store(mydoc)
                }
            }
        }
    }

    def transform(a_json, rtype) {
        def alla_json = []
        def resten_json = [:]
        def link
        def id001

        def top_title

        for (def f in a_json["fields"]) {
            f.each { k, v ->
                if (k == "001") {
                    link = "/${rtype}/${v}"
                    id001 = v
                }
                if (k in ["100", "700"]) {
                    def should_add = true
                    def sug_json = [:]
                    sug_json["100"] = [:]
                    sug_json["100"]["ind1"] = v["ind1"]
                    for (def sf in v["subfields"]) {
                        sf.each {sk, sv -> 
                            if (sk == "0" && rtype == "bib") {
                                should_add = false
                            }
                            if (["a","b","c","d"].contains(sk)) {
                                sug_json["100"][sk] = sv
                            }
                        }
                    }
                    if (should_add) {
                        if (k == "100") {
                            sug_json["identifier"] = "/${w_name}/${suggest_source}/${id001}"
                        } else {
                            def name = "${id001}/" + sug_json["100"]["a"].replace(",","").replace(" ", "_").replace(".","").replace("[","").replace("]","")

                            //print "values", w_name, suggest_source, "name:", name
                            sug_json["identifier"] = "/${w_name}/${suggest_source}/${name}"
                        }
                        alla_json << sug_json

                    }
                } else if (k in get_fields(rtype)) {
                    def f_list = (resten_json[k] ? resten_json[k] : [])
                    def d = [:]
                    for (sf in v["subfields"]) {
                        sf.each { sk, sv -> 
                            d[sk] = sv
                        }
                    }
                    f_list << d
                    if (k == "245") {
                        def titleparts = [:]
                        for (sf in v["subfields"]) {
                            sf.each { sk, sv ->
                                if (sk in ["a", "b"]) {
                                    titleparts[sk] = sv.replaceAll(/^[\s\/\[\]]+|[\s\/\[\]]+$/, "")
                                }
                            }
                        }

                        //print "titleparts", titleparts
                        top_title = titleparts["a"] + " " + (titleparts["b"] ? titleparts["b"] : "")
                    }

                    resten_json[k] = f_list
                }
            }
        }

        //print "sug_json", sug_json
        //print "resten_json", resten_json
        //print "alla_json", alla_json

        resten_json["records"] = 1

        // get_records for auth-records
        if (rtype == "auth") {
            if (alla_json.size() > 0 && alla_json[0]["100"]) {
                //f_100 = " ".join(alla_json[0]["100"].values()[1:])
                def f_100 = alla_json[0]["100"]
                resten_json = get_records(f_100, resten_json)
            }
        } 
        else if (top_title) { 
            // single top-title for bibrecords
            resten_json["top_titles"] = ["http://libris.kb.se${link}" : top_title.trim()]
        }

        // More for resten
        if (link) {
            resten_json["link"] = link
        }
        resten_json["authorized"] = (rtype == "auth" ? true : false)

            // cleanup
            try {
                resten_json.pop("245")
            } catch (Exception e) {}

        // append resten on alla
        //print "resten", resten_json
        def ny_alla = []
        for (def my_json : alla_json) {
            ny_alla << my_json + resten_json
            //ny_alla.append(dict(my_json.items() + resten_json.items()))
        }

        //print "alla", ny_alla
        return ny_alla

    }

    def get_fields(rtype) {
        if (rtype == "auth") {
            return ["400", "500", "678", "680", "856"]
        }
        return ["245", "678"]
    }

    def get_records(f_100, sug_json) {
        if (bibwhelk) {
            query_100 = []
            query_700 = []
            f_100.each { k, v ->
                if (["a","b","c","d"].contains(k)) {
                    query_100 << "fields.100.subfields.${k}:\"${v}\""
                    query_700 << "fields.700.subfields.${k}:\"${v}\""
                }
            }
            q_100 = query.join(" AND ")
            q_700 = query_700.join(" AND ")
            q_swe = "fields.008:swe"

            q_all = "(($q_100) OR ($q_700)) AND $q_swe"

            response = bibwhelk.query(q_all) 
            //print "Count: ", response.getNumberOfHits()
            sug_json["records"] = response.getNumberOfHits()

            def top_3 = [:]
            for (document in response.hits[0..2]) {
                jdoc = new JsonSlurper().parseText(document.getDataAsString())
                def (f_001, title) = top_title_tuple(jdoc["fields"])
                top_3[f_001] = title
            }

            def top_missing = 5 - top_3.size()
            q_all = "($q_100) OR ($q_700)"

            response = bibwhelk.query(q_all) 
            //print "Count: ", response.getNumberOfHits()
            sug_json["records"] = response.getNumberOfHits()
            for (document in response.hits[0..(top_missing)]) {
                jdoc = json.loads(document.getDataAsString())
                def (f_001, title) = top_title_tuple(jdoc["fields"])
                top_3[f_001] = title
            }

            //print "top_3", top_3
            only_top_3 = [:]
            top_3[0..2].each { k, v ->
                only_top_3[k] = v
            }
            sug_json["top_titles"] = only_top_3
        }
        return sug_json
    }

    def top_title_tuple(fields) {
        f_001 = ""
        f_245_a = ""
        f_245_b = ""
        f_245_n = ""
        for (field in fields) {
            field.each { k,v ->
                if (k == "001") {
                    f_001 = v
                }
                else if (k == "245") {
                    for (sf in v["subfields"]) {
                        sf.each { sk, sv ->
                            if (sk == "a") {
                                f_245_a = sv
                            }
                            else if (sk == "b") {
                                f_245_b = sv
                            }
                            else if (sk == "n") {
                                f_245_n = sv
                            }
                        }
                    }
                }

            }
        }
        if (f_001 && f_245_a) {
            //title = f_245_a.trim("[:;/.]") + f_245_b.trim("[:;/]") + " " + f_245_n.trim("[:;/ ]")
            title = f_245_a.replaceAll(/^[\[:;\/\.\]]+|[\[:;\/\.\]]+$/, "") + f_245_b.replaceAll(/^[\[:;\/\]]+|[\[:;\/\]]+$/, "") + " " + f_245_n.replaceAll(/^[\[:;\/\s\]]+|[\[:;\/\s\]]+$/, "")
        }
        return [f_001, title.trim()]
    }


    def _get_records(f_100, sug_json) {
        try {
            url = "http://libris.kb.se/xsearch"
            values = ["query" : "forf:(%s) spr:swe" % f_100, "format" : "json"]

            data = urllib.urlencode(values)
            //print "XSEARCH URL: %s?%s" % (url, data)
            reply = urllib2.urlopen(url + "?" + data)

            response = reply.read().decode("utf-8")
            //print "got response", response, type(response)

            xresult = json.loads(response)["xsearch"]

            sug_json["records"] = xresult["records"]
            top_3 = xresult["list"][0..2]
            top_titles = {}
            for (p in top_3) {
                top_titles[p["identifier"]] = unicode(p["title"])
            }
            sug_json["top_titles"] = top_titles
        }
        catch (Exception e) {
            print "exception in get_records"
        }
        return sug_json
    }

    def record_type(leader) {
        if (leader[6] == "z") {
            return "auth"
        }
        return "bib"
    }
}