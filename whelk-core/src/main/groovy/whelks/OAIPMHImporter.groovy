package se.kb.libris.whelks.importers

import groovy.xml.StreamingMarkupBuilder
import groovy.util.slurpersupport.GPathResult
import groovy.util.logging.Slf4j as Log

import java.text.*

import se.kb.libris.whelks.*
import se.kb.libris.whelks.exception.*
import se.kb.libris.util.marc.*
import se.kb.libris.util.marc.io.*
import se.kb.libris.conch.converter.MarcJSONConverter

@Log
class OAIPMHImporter {

    Whelk whelk
    String resource
    int nrImported = 0
    int nrDeleted = 0
    long startTime = 0

    OAIPMHImporter(Whelk toWhelk, String fromResource) {
        this.whelk = toWhelk
        this.resource = fromResource
    }

    int doImport(Date from = null) {
        getAuthentication()
        String urlString = "http://data.libris.kb.se/"+this.resource+"/oaipmh/?verb=ListRecords&metadataPrefix=marcxml"
        if (from) {
            urlString = urlString + "&from=" + from.format("yyyy-MM-dd'T'HH:mm:ss'Z'")
        }
        startTime = System.currentTimeMillis()
        log.info("Harvesting OAIPMH data from $urlString")
        URL url = new URL(urlString)
        String resumptionToken = harvest(url)
        log.debug("resumptionToken: $resumptionToken")
        while (resumptionToken) {
            url = new URL("http://data.libris.kb.se/" + this.resource + "/oaipmh/?verb=ListRecords&resumptionToken=" + resumptionToken)
            resumptionToken = harvest(url)
            log.debug("resumptionToken: $resumptionToken")
        }
        return nrImported
    }


    String harvest(URL url) {
        long loadStartTime = System.currentTimeMillis()
        def xmlString = normalizeString(url.text)
        long meanTime = System.currentTimeMillis()
        def OAIPMH
        try {
            OAIPMH = new XmlSlurper(false,false).parseText(xmlString)
        } catch (org.xml.sax.SAXParseException spe) {
            log.error("Failed to parse XML: $xmlString")
            throw spe
        }
        def documents = []
        OAIPMH.ListRecords.record.each {
            def mdrecord = createString(it.metadata.record)
            if (mdrecord) {
                MarcRecord record = MarcXmlRecordReader.fromXml(mdrecord)

                String id = record.getControlfields("001").get(0).getData()
                String jsonRec = MarcJSONConverter.toJSONString(record)

                def doc

                try {
                    doc = whelk.createDocument(jsonRec.getBytes("UTF-8"), ["identifier":new URI("/"+whelk.prefix+"/"+id),"contentType":"application/json","format":"marc21"])
                } catch (Exception e) {
                    log.error("Failed! (${e.message}) for :\n$mdrecord")
                    throw e
                }
                if (it.header.setSpec) {
                    for (sS in it.header.setSpec) {
                        if (sS.toString().startsWith("authority:")) {
                            def auth = "/auth/" + sS.toString().substring(10)
                            doc = doc.withLink(auth, "authority")
                        }
                        if (sS.toString().startsWith("location:")) {
                            doc = doc.tag("location", sS.toString().substring(9))
                        }
                        if (sS.toString().startsWith("bibid:")) {
                            doc = doc.withLink("/bib/" + sS.toString().substring(6), "bib")
                        }
                    }
                }
                documents << doc
                nrImported++
            } else if (it.header.@deleted == 'true') {
                String deleteIdentifier = "/" + new URI(it.header.identifier.text()).getPath().split("/")[2 .. -1].join("/")
                whelk.delete(new URI(deleteIdentifier))
                nrDeleted++
            } else {
                throw new WhelkRuntimeException("Failed to handle record: " + createString(it))
            }
        }
        long conversionTime = System.currentTimeMillis()
        whelk.bulkStore(documents)
        int sizeOfBatch = documents.size()

        long storageTime = System.currentTimeMillis()
        log.debug("Loaded sofar: $nrImported. Times (in milliseconds): [Load URL: " +(meanTime - loadStartTime)+ "] [Convert: "+(conversionTime - meanTime)+"] [Store: "+(storageTime - conversionTime)+"] Velocity: " + ((sizeOfBatch/((System.currentTimeMillis() - loadStartTime)/1000)) as int) + " docs/sec.")

        if (!OAIPMH.ListRecords.resumptionToken.text()) {
            log.info("Last page is $xmlString")
        }
        return OAIPMH.ListRecords.resumptionToken
    }

    private void getAuthentication() {
        try {
            Properties properties = new Properties()
            properties.load(this.getClass().getClassLoader().getResourceAsStream("whelks-core.properties"))
            final String username = properties.getProperty("username")
            final String password = properties.getProperty("password")
            Authenticator.setDefault(new Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password.toCharArray())
                    }
                });
        } catch (Exception ex) {
            log.error("Exception: $ex")
        }
    }

    String normalizeString(String inString) {
        if (!Normalizer.isNormalized(inString, Normalizer.Form.NFC)) {
            log.trace("Normalizing ...")
            return Normalizer.normalize(inString, Normalizer.Form.NFC)
        }
        return inString
    }

    String createString(GPathResult root) {
        return new StreamingMarkupBuilder().bind{
            out << root
        }
    }
}