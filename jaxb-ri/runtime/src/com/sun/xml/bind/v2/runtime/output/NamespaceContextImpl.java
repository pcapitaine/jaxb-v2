package com.sun.xml.bind.v2.runtime.output;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamException;

import com.sun.xml.bind.marshaller.NamespacePrefixMapper;
import com.sun.xml.bind.v2.WellKnownNamespace;
import com.sun.xml.bind.v2.runtime.Name;
import com.sun.xml.bind.v2.runtime.NamespaceContext2;
import com.sun.xml.bind.v2.runtime.XMLSerializer;

import org.xml.sax.SAXException;

/**
 * Keeps track of in-scope namespace bindings for the marshaller.
 *
 * <p>
 * This class is also used to keep track of tag names for each element
 * for the marshaller.
 *
 * @author Kohsuke Kawaguchi
 */
public final class NamespaceContextImpl implements NamespaceContext2 {
    private final XMLSerializer owner;

    private String[] prefixes = new String[4];
    private String[] nsUris = new String[4];

    /**
     * Number of URIs declared. Identifies the valid portion of
     * the {@link #prefixes} and {@link #nsUris} arrays.
     */
    private int size;

    private Element current;

    private final Element root;

    /**
     * Never null.
     */
    private NamespacePrefixMapper prefixMapper = defaultNamespacePrefixMapper;

    /**
     * True to allow new URIs to be declared. False otherwise.
     */
    public boolean collectionMode;


    public NamespaceContextImpl(XMLSerializer owner) {
        this.owner = owner;

        current = root = new Element(this,null);
        // register namespace URIs that are implicitly bound
        put(XMLConstants.XML_NS_URI,XMLConstants.XML_NS_PREFIX);
    }

    public void setPrefixMapper( NamespacePrefixMapper mapper ) {
        if(mapper==null)
            mapper = defaultNamespacePrefixMapper;
        this.prefixMapper = mapper;
    }

    public NamespacePrefixMapper getPrefixMapper() {
        return prefixMapper;
    }

    public void reset() {
        current = root;
        size = 1;
        collectionMode = false;
    }

    /**
     * Returns the prefix index to the specified URI.
     * This method allocates a new URI if necessary.
     */
    public int declareNsUri( String uri, String preferedPrefix, boolean requirePrefix ) {
        preferedPrefix = prefixMapper.getPreferredPrefix(uri,preferedPrefix,requirePrefix);

        if(uri.length()==0) {
            for( int i=size-1; i>=0; i-- ) {
                if(nsUris[i].length()==0)
                    return i; // already declared
                if(prefixes[i].length()==0) {
                    // the default prefix is already taken.
                    // move that URI to another prefix, then assign "" to the default prefix.
                    assert current.defaultPrefixIndex==-1 && current.oldDefaultNamespaceUriIndex==-1;

                    String oldUri = nsUris[i];
                    String[] knownURIs = owner.nameList.namespaceURIs;

                    if(current.baseIndex<=i) {
                        // this default prefix is declared in this context. just reassign it

                        nsUris[i] = "";

                        int subst = put(oldUri,null);

                        // update uri->prefix table if necessary
                        for( int j=knownURIs.length-1; j>=0; j-- ) {
                            if(knownURIs[j].equals(oldUri)) {
                                owner.knownUri2prefixIndexMap[j] = subst;
                                break;
                            }
                        }

                        return i;
                    } else {
                        // first, if the previous URI assigned to "" is
                        // a "known URI", remember what we've reallocated
                        // so that we can fix it when this context pops.
                        for( int j=knownURIs.length-1; j>=0; j-- ) {
                            if(knownURIs[j].equals(oldUri)) {
                                current.defaultPrefixIndex = i;
                                current.oldDefaultNamespaceUriIndex = j;
                                assert owner.knownUri2prefixIndexMap[j]==current.defaultPrefixIndex;
                                // update the table to point to the prefix we'll declare
                                owner.knownUri2prefixIndexMap[j] = size;
                                break;
                            }
                        }

                        put(nsUris[i],null);
                        return put("", "");
                    }
                }
            }

            // "" isn't in use
            return put("", "");
        } else {
            // check for the existing binding
            for( int i=size-1; i>=0; i-- ) {
                String p = prefixes[i];
                if(nsUris[i].equals(uri)) {
                    if(!requirePrefix || p.length()>0)
                        return i;
                    // declared but this URI is bound to empty. Look further
                }
                if(p.equals(preferedPrefix)) {
                    // the suggested prefix is already taken. can't use it
                    preferedPrefix = null;
                }
            }

            // haven't been declared. allocate a new one
            // if the preferred prefix is already in use, it should have been set to null by this time
            return put(uri, preferedPrefix);
        }
    }

    /**
     * Puts this new binding into the declared prefixes list.
     *
     * @param prefix
     *      if null, an unique prefix is allocated.
     *
     * @return
     *      the index of this new binding.
     */
    private int put(String uri, String prefix) {
        if(size==nsUris.length) {
            // reallocate
            String[] u = new String[nsUris.length*2];
            String[] p = new String[prefixes.length*2];
            System.arraycopy(nsUris,0,u,0,nsUris.length);
            System.arraycopy(prefixes,0,p,0,prefixes.length);
            nsUris = u;
            prefixes = p;
        }
        if(prefix==null) {
            if(size==1)
                prefix = "";    // if this is the first user namespace URI we see, use "".
            else
                // otherwise make up an unique name 
                prefix = new StringBuilder(5).append("ns").append(size).toString();
        }
        nsUris[size] = uri;
        prefixes[size] = prefix;

        return size++;
    }


    public Element getCurrent() {
        return current;
    }

    /**
     * Returns the prefix index of the specified URI.
     * It is an error if the URI is not declared.
     */
    public int getPrefixIndex( String uri ) {
        for( int i=size-1; i>=0; i-- ) {
                if(nsUris[i].equals(uri))
                    return i;
        }
        throw new IllegalStateException();
    }

    /**
     * Gets the prefix from a prefix index.
     *
     * The behavior is undefined if the index is out of range.
     */
    public String getPrefix(int prefixIndex) {
        return prefixes[prefixIndex];
    }

    public String getNamespaceURI(int prefixIndex) {
        return nsUris[prefixIndex];
    }

    /**
     * Gets the namespace URI that is bound to the specified prefix.
     *
     * @return null
     *      if the prefix is unbound.
     */
    public String getNamespaceURI(String prefix) {
        for( int i=size-1; i>=0; i-- )
            if(prefixes[i].equals(prefix))
                return nsUris[i];
        return null;
    }

    /**
     * Returns the prefix of the specified URI,
     * or null if none exists.
     */
    public String getPrefix( String uri ) {
        if(collectionMode) {
            return declareNamespace(uri,null,false);
        } else {
            for( int i=size-1; i>=0; i-- )
                if(nsUris[i].equals(uri))
                    return prefixes[i];
            return null;
        }
    }

    public Iterator<String> getPrefixes(String uri) {
        String prefix = getPrefix(uri);
        if(prefix==null)
            return Collections.<String>emptySet().iterator();
        else
            return Collections.singleton(uri).iterator();
    }

    public String declareNamespace(String namespaceUri, String preferedPrefix, boolean requirePrefix) {
        int idx = declareNsUri(namespaceUri,preferedPrefix,requirePrefix);
        return getPrefix(idx);
    }

    /**
     * Number of total bindings declared.
     */
    public int count() {
        return size;
    }


    /**
     * This model of namespace declarations maintain the following invariants.
     *
     * <ul>
     *  <li>If a non-empty prefix is declared, it will never be reassigned to different namespace URIs.
     *  <li>
     */
    public final class Element {

        public final NamespaceContextImpl context;

        /**
         * {@link Element}s form a doubly-linked list.
         */
        private final Element prev;
        private Element next;

        private int oldDefaultNamespaceUriIndex;
        private int defaultPrefixIndex;


        /**
         * The numbe of prefixes declared by ancestor {@link Element}s.
         */
        private int baseIndex;



        private int elementNamePrefix;
        private String elementLocalName;

        /**
         * Tag name of this element.
         * Either this field is used or the {@link #elementNamePrefix} and {@link #elementLocalName} pair.
         */
        private Name elementName;

        /**
         * Used for the binder. The JAXB object that corresponds to this element.
         */
        private Object outerPeer;
        private Object innerPeer;


        private Element(NamespaceContextImpl context,Element prev) {
            this.context = context;
            this.prev = prev;
        }

        public Element push() {
            if(next==null)
                next = new Element(context,this);
            next.onPushed();
            return next;
        }

        public Element pop() {
            if(oldDefaultNamespaceUriIndex>=0) {
                // restore the old default namespace URI binding
                context.owner.knownUri2prefixIndexMap[oldDefaultNamespaceUriIndex] = defaultPrefixIndex;
            }
            context.size = baseIndex;
            context.current = prev;
            return prev;
        }

        private void onPushed() {
            oldDefaultNamespaceUriIndex = defaultPrefixIndex = -1;
            baseIndex = context.size;
            context.current = this;
        }

        /**
         * Returns true if this {@link Element} is the root of the stack.
         * The root is used to keep track of the implicit bindings.
         */
        public boolean isRoot() {
            return prev==null;
        }

        public void setTagName( int prefix, String localName, Object outerPeer ) {
            assert localName!=null;
            this.elementNamePrefix = prefix;
            this.elementLocalName = localName;
            this.elementName = null;
            this.outerPeer = outerPeer;
        }

        public void setTagName( Name tagName, Object outerPeer ) {
            assert tagName!=null;
            this.elementName = tagName;
            this.outerPeer = outerPeer;
        }

        public void startElement(XmlOutput out, Object innerPeer) throws IOException, XMLStreamException {
            this.innerPeer = innerPeer;
            if(elementName!=null) {
                out.beginStartTag(elementName);
            } else {
                out.beginStartTag(elementNamePrefix,elementLocalName);
            }
        }

        public void endElement(XmlOutput out) throws IOException, SAXException, XMLStreamException {
            if(elementName!=null) {
                out.endTag(elementName);
                elementName = null;
            } else {
                out.endTag(elementNamePrefix,elementLocalName);
            }
        }

        /**
         * Gets the number of bindings declared on this element.
         */
        public final int count() {
            return context.size-baseIndex;
        }

        /**
         * Gets the prefix declared in this context.
         *
         * @param idx
         *      between 0 and {@link #count()}
         */
        public final String getPrefix(int idx) {
            return context.prefixes[baseIndex+idx];
        }

        /**
         * Gets the namespace URI declared in this context.
         *
         * @param idx
         *      between 0 and {@link #count()}
         */
        public final String getNsUri(int idx) {
            return context.nsUris[baseIndex+idx];
        }

        public int getBase() {
            return baseIndex;
        }

        public Object getOuterPeer() {
            return outerPeer;
        }

        public Object getInnerPeer() {
            return innerPeer;
        }
    }


    /**
     * Default {@link NamespacePrefixMapper} implementation used when
     * it is not specified by the user.
     */
    private static final NamespacePrefixMapper defaultNamespacePrefixMapper = new NamespacePrefixMapper() {
        public String getPreferredPrefix(String namespaceUri, String suggestion, boolean requirePrefix) {
            if( namespaceUri.equals(WellKnownNamespace.XML_SCHEMA_INSTANCE) )
                return "xsi";
            if( namespaceUri.equals(WellKnownNamespace.XML_SCHEMA) )
                return "xs";
            if( namespaceUri.equals(WellKnownNamespace.XML_MIME_URI) )
                return "xmime";
            return suggestion;
        }
    };
}
