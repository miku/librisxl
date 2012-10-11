package se.kb.libris.whelks.component

import se.kb.libris.whelks.Document
import se.kb.libris.whelks.basic.BasicDocument
import se.kb.libris.whelks.Key
import se.kb.libris.whelks.LookupResult
import se.kb.libris.whelks.exception.WhelkException
import groovy.util.logging.Slf4j as Log
import java.net.URL
import java.net.HttpURLConnection
import com.basho.riak.client.IRiakClient
import com.basho.riak.client.bucket.Bucket
import com.basho.riak.client.RiakFactory
import com.basho.riak.client.operations.StoreObject
import com.basho.riak.client.RiakException

@Log
class RiakStorage implements Storage {
    private IRiakClient riakClient
    private byte[] clientId
    private Bucket riakBucket
    private int riak_port
    private String plugin_id = "riakstore"
    private boolean enabled = true
    static final int HTTP_CREATED = 201

    public RiakStorage(String bucket){
        try {
            Properties properties = new Properties()
            def propInputStream = RiakStorage.class.getClassLoader().getResourceAsStream("whelks-core.properties")
            properties.load(propInputStream)
            def riak_host = properties.getProperty("riakhost")
            def riak_port = Integer.parseInt(properties.getProperty("riakport"))
            def url = "http://" + riak_host + ":" + riak_port + "/riak/"
            riakClient = RiakFactory.httpClient(url)
            log.info("Loading RiakClient " + url)
            clientId = riakClient.generateAndSetClientId()
            riakBucket = riakClient.createBucket(bucket).execute()
         } catch(RiakException re) {
            log.debug("Could not intialize riak httpclient for: " + url + " Bucket: " + bucket + " " + e.getMessage())
        }
   }

    public String getId(){ return this.clientId }
    public boolean isEnabled(){ return this.enabled }
    public void enable(){ this.enabled = true }
    public void disable(){ this.enabled = false }

    public void store(Document d){  
        try {
            final String id = UUID.randomUUID().toString()
            this.riakBucket.store(id, d.data).execute()
            log.debug("Stored object with id: " + id)
        } catch (Exception e) {
            log.debug("Exception in store: " + e.printStackTrace())
        }
    }

    public void store(Iterable<Document> d){
        for (def doc : d) {
            store(doc)
        }
    }

    public Document get(URI uri){
        throw new UnsupportedOperationException("Not supported yet.")
    }

    public Iterable<Document> getAll(){
        throw new UnsupportedOperationException("Not supported yet.")
    }

    public void delete(URI uri){
        throw new UnsupportedOperationException("Not supported yet.")
    }

    public LookupResult<? extends Document> lookup(Key key){
        throw new UnsupportedOperationException("Not supported yet.")
    }
	
}