package se.kb.libris.whelks.plugin

import se.kb.libris.conch.Tools
import se.kb.libris.whelks.*
import se.kb.libris.whelks.basic.*
import se.kb.libris.whelks.exception.*

import groovy.util.logging.Slf4j as Log
import org.codehaus.jackson.map.ObjectMapper

@Log
class BasicMarc2JsonLDConverter extends BasicFormatConverter implements FormatConverter, IndexFormatConverter {

    String requiredContentType = "application/json"
    String requiredFormat = "marc21"
    String RTYPE
    ObjectMapper mapper

    def marcref
    def marcmap

    BasicMarc2JsonLDConverter(String rt) {
        this.RTYPE = rt
        mapper = new ObjectMapper()
        InputStream is = this.getClass().getClassLoader().getResourceAsStream("marc_refs.json")
        this.marcref = mapper.readValue(is, Map)
        is = this.getClass().getClassLoader().getResourceAsStream("marcmap.json")
        this.marcmap = mapper.readValue(is, Map)
    }

    def mapField(outjson, code, fjson, docjson) {
        if (marcref[RTYPE].mapping[code]) {
            def usecode = (marcref[RTYPE].mapping[code] instanceof Map ? code : marcref[RTYPE].mapping[code])
            marcref[RTYPE].mapping[usecode].each { property, fieldcode ->
                log.trace("Examining mapping: $property -- $fieldcode")
                if (fieldcode instanceof String) {
                    if (fieldcode.startsWith("CONSTANT:")) {
                        def v = fieldcode.substring(9)
                        outjson = Tools.insertAt(outjson, property, v)
                    } else if (fieldcode.startsWith(usecode)) {
                        def v = getMarcValueFromField(fieldcode, fjson)
                        if (v) {
                            outjson = Tools.insertAt(outjson, property, v)
                        }
                    } else {
                        getMarcField(fieldcode, docjson).each {
                            if (it) {
                                log.trace("inserting at $property: $it")
                                outjson = Tools.insertAt(outjson, property, it)
                            }
                        }
                    }
                } else if (fieldcode instanceof Map) {
                    def obj = [:]
                    fieldcode.each { item, fcode ->
                        def v
                        if (fcode.startsWith("CONSTANT:")) {
                            v = fcode.substring(9)
                        } else {
                            v = getMarcValueFromField(fcode, fjson)
                        }
                        if (v) {
                            obj[(item)] = v
                        }
                    }
                    outjson = Tools.insertAt(outjson, property, obj)
                }
            }

        } else if (marcref[RTYPE].process[code]) {
            def proclist = marcref[RTYPE].process[code]
            if (proclist instanceof Map) {
                proclist = [proclist]
            }
            for (proc in proclist) {
                log.trace("call method ${proc.method}")
                def mapping = "${proc.method}"(outjson, code, fjson, docjson)
                log.trace("result mapping: $mapping")
                if (mapping) {
                    outjson = Tools.insertAt(outjson, proc.level, mapping)
                }
            }
        } else if (!(marcref[RTYPE].handled_elsewhere[code])) {
            outjson = dropToRaw(outjson, [(code):fjson])
        }
        //log.trace("outjson: $outjson")
        return outjson
    }

    List<Document> doConvert(Document doc) {
        def injson = doc.dataAsJson
        def outjson = ["@id":doc.identifier.toString()]
        outjson["@type"] = detectRecordType(injson)

        for (field in injson.fields) {
            field.each { code, fjson ->
                outjson = mapField(outjson, code, fjson, injson)
            }
        }

        return [new BasicDocument(doc).withData(mapper.writeValueAsBytes(outjson)).withFormat("jsonld")]
    }



    // Utility methods
    String detectRecordType(marcjson) {
        log.debug("leader: ${marcjson.leader}")
        def typeOfRecord = marcjson.leader[6]
        def bibLevel = marcjson.leader[7]
        def carrierType = getControlField("007", marcjson)?.charAt(0)
        if (typeOfRecord == "a" && bibLevel == "m" && carrierType != "c") {
            return "Book"
        }
        if (typeOfRecord == "i" && bibLevel == "m" && carrierType == "s") {
            return "Audiobook"
        }
        if (typeOfRecord == "a" && bibLevel == "s" && carrierType != "c") {
            return "Serial"
        }
        def computerMaterial = getControlField("007", marcjson)?.charAt(1)
        if (typeOfRecord == "a" && bibLevel == "m" && carrierType == "c" && computerMaterial == "r") {
            return "Ebook"
        }
        if (typeOfRecord == "a" && bibLevel == "s" && carrierType == "c" && computerMaterial == "r") {
            return "Eserial"
        }
        return "Unknown"
    }

    def dropToRaw(outjson, marcjson) {
        outjson = Tools.insertAt(outjson, "unknown.fields", marcjson)
    }

    def getControlField(field, marcjson) {
        def value
        marcjson.fields.each {
            it.each { f, v ->
                if (field == f) {
                    value = v
                }
            }
        }
        return value
    }

    def getMarcField(fieldcode, marcjson, joinChar=" ") {
        def (field, code) = fieldcode.split(/\./, 2)
        def values = []
        log.trace("field: $field, code: $code, codes: ${code.toList()}")
        marcjson.fields.each {
            it.each { f, v ->
                if (f == field) {
                    log.trace("calling getvalue at $f")
                    values << getMarcValueFromField(code, v, joinChar)
                }
            }
        }
        log.trace("extracted values: $values")
        return values
    }

    def getMarcValueFromField(code, fieldjson, joinChar=" ") {
        def codeValues = []
        log.trace("getMarcValueFromField: $code - $fieldjson")
        if (fieldjson instanceof Map) {
            fieldjson.subfields.each { s ->
                s.each {sc, sv ->
                    if (sc in code.toList()) {
                        codeValues << sv
                    }
                }
            }
        } else {
            return fieldjson
        }
        log.trace("codeValues: $codeValues")
        return codeValues.join(joinChar)
    }

    // Mapping methods
    def mapPerson(outjson, code, fjson, marcjson) {
        log.trace("person json: $fjson")
        def person = [:]
        if ((code as int) % 100 == 0) {
            person["@type"] = "Person"
        }
        if ((code as int) % 110 == 0) {
            person["@type"] = "Organization"
        }
        if ((code as int) % 111 == 0) {
            person["@type"] = "Conference"
        }
        def name = getMarcValueFromField("a", fjson)
        def numeration = getMarcValueFromField("b", fjson)
        def title = getMarcValueFromField("c", fjson)
        def dates = getMarcValueFromField("d", fjson)
        person["authoritativeName"] = name.replaceAll(/,$/, "").trim()
        person["authorizedAccessPoint"] = name.replaceAll(/,$/, "").trim()
        if (numeration) {
            person["authorizedAccessPoint"] = person["authorizedAccessPoint"] + " " + numeration
            person["numeration"] = numeration
        }
        if (title) {
            person["titlesAndOtherWordsAssociatedWithName"] = title
            person["authorizedAccessPoint"] = person["authorizedAccessPoint"] + ", " + title

        }
        if (dates) {
            person["authorizedAccessPoint"] = person["authorizedAccessPoint"] + ", " + dates
            dates = dates.split(/-/)
            person["birthYear"] = dates[0]
            if (dates.size() > 1) {
                person["deathYear"] = dates[1]
            }
        }
        if (fjson.ind1 == "1" && person["authoritativeName"] =~ /, /) {
            person["givenName"] = person["authoritativeName"].split(", ")[1]
            person["familyName"] = person["authoritativeName"].split(", ")[0]
            person["name"] =  person["givenName"] + " " + person["familyName"]
        } else {
            person["name"] = person["authoritativeName"]
        }
        log.trace("person: $person")
        return person
    }
}