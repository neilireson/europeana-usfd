package org.apache.solr.handler.component;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.org.apache.xerces.internal.dom.ElementImpl;
import org.apache.solr.cloud.ZkSolrResourceLoader;
import org.apache.solr.core.Config;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.MultiMapSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;

public class AliasingRequestHandler
        extends SearchHandler {

    /**
     * This class is a remnant form when the configuration was loaded as part of the initial Solr configuration.
     * However that involved changing and recompiling the whole Solr cadebase. Now the configuration for each core
     * is stored in a static map and read when the first AliasingRequestHandler is initialised
     */
    public static class AliasConfig
            extends Config {

        private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
        private static final String DEFAULT_CONF_FILE = "query_aliases.xml";
        private final HashMap<String, HashMap<String, String>> aliases;

        /**
         * Creates a default instance from query_aliases.xml.
         */
        public AliasConfig()
                throws ParserConfigurationException, IOException, SAXException {
            this((SolrResourceLoader) null, DEFAULT_CONF_FILE, null);
        }

        /**
         * Creates a configuration instance from a configuration name.
         * A default resource loader will be created (@see SolrResourceLoader)
         *
         * @param name the configuration name used by the loader
         */
        public AliasConfig(String name)
                throws ParserConfigurationException, IOException, SAXException {
            this((SolrResourceLoader) null, name, null);
        }

        /**
         * Creates a configuration instance from a configuration name and stream.
         * A default resource loader will be created (@see SolrResourceLoader).
         * If the stream is null, the resource loader will open the configuration stream.
         * If the stream is not null, no attempt to load the resource will occur (the name is not used).
         *
         * @param name the configuration name
         * @param is   the configuration stream
         */
        public AliasConfig(String name, InputSource is)
                throws ParserConfigurationException, IOException, SAXException {
            this((SolrResourceLoader) null, name, is);
        }

        /**
         * Creates a configuration instance from an instance directory, configuration name and stream.
         *
         * @param instanceDir the directory used to create the resource loader
         * @param name        the configuration name used by the loader if the stream is null
         * @param is          the configuration stream
         */
        public AliasConfig(Path instanceDir, String name, InputSource is)
                throws ParserConfigurationException, IOException, SAXException {
            this(new SolrResourceLoader(instanceDir), name, is);
        }


        public AliasConfig(SolrResourceLoader loader, String name, InputSource is)
                throws ParserConfigurationException, IOException, SAXException {

            super(loader, AliasConfig.DEFAULT_CONF_FILE, is, "/alias-configs/");
            this.aliases = populateAliases();
            log.info("Loaded Aliases Config: " + name);
        }

        private HashMap<String, HashMap<String, String>> populateAliases() {

            HashMap<String, HashMap<String, String>> allAliases = new HashMap<>();
            NodeList aliasFields = (NodeList) evaluate("alias-config", XPathConstants.NODESET);
            for (int i = 0; i < aliasFields.getLength(); i++) {
                ElementImpl pseudofieldNode = (ElementImpl) aliasFields.item(i);
                String fieldName = pseudofieldNode.getElementsByTagName("alias-pseudofield").item(0).getTextContent();
                NodeList configs = pseudofieldNode.getElementsByTagName("alias-def");
                HashMap<String, String> aliasMap = new HashMap<>();
                for (int j = 0; j < configs.getLength(); j++) {
                    ElementImpl configNode = (ElementImpl) configs.item(j);
                    String alias = configNode.getElementsByTagName("alias").item(0).getTextContent();
                    String query = configNode.getElementsByTagName("query").item(0).getTextContent();
                    aliasMap.put(alias, query);
                }
                allAliases.put(fieldName, aliasMap);
            }

            return allAliases;
        }

        public HashMap<String, HashMap<String, String>> getAliases() {
            return this.aliases;
        }

        public static AliasConfig readFromResourceLoader(SolrResourceLoader loader, String name) {
            try {
                return new AliasConfig(loader, name, null);
            }
            catch (Exception e) {
                String resource;
                if (loader instanceof ZkSolrResourceLoader) {
                    resource = name;
                } else {
                    resource = loader.getConfigDir() + name;
                }
                throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error loading aliasing config from " + resource, e);
            }
        }
    }

    // TODO: check that the interfaces implemented in SearchHandler are done so
    // in a fashion suitable to this subclass as well

    private static final Map<SolrCore, AliasConfig> coreAliasConfigMap = new HashMap<>();

    public void init(NamedList params) {
        super.init(params);
    }

    /**
     * Expands aliases into queries for thematic collections.
     *
     * Operates by taking apart the passed request, scanning it for thematic-collection keywords,
     * and replacing those it finds with the appropriate expanded query.
     *
     * @author thill
     * @version 2017.11.14
     */

    @Override
    public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp)
            throws Exception {

        SolrCore core = req.getCore();
        AliasConfig aliasConfig = coreAliasConfigMap.get(core);
        if (aliasConfig == null) {
            // Note it is possible to use the init param to parameterise the AliasConfig constructor
            aliasConfig = new AliasConfig();
            coreAliasConfigMap.put(core, aliasConfig);
        }
        HashMap<String, HashMap<String, String>> aliases = aliasConfig.getAliases();
        SolrParams params = req.getParams();
        Iterator<String> pnit = params.getParameterNamesIterator();
        Map<String, String[]> modifiedParams = new HashMap<String, String[]>();
        while (pnit.hasNext()) {
            String pname = pnit.next();
            String[] pvalues = params.getParams(pname);
            if (!pname.equals("q") && !pname.equals("fq")) {
                modifiedParams.put(pname, pvalues);
            } else {
                String[] modifiedValues = this.modifyValues(aliases, pvalues);
                modifiedParams.put(pname, modifiedValues);
            }
        }
        MultiMapSolrParams newParams = new MultiMapSolrParams(modifiedParams);
        req.setParams(newParams);
        // a little debugging check
        //String paramCheck = outputParams(newParams);
        // rsp.add("test", paramCheck);
        super.handleRequestBody(req, rsp);

    }

    /*
     * Given a HashMap listing pseudofields and their appropriate aliases and expansions,
     * scans the passed list of parameters for pseudofields and swaps in expanded queries as
     * appropriate.
     *
     * @author thill
     * @version 2017.11.14
     */

    private String[] modifyValues(HashMap<String, HashMap<String, String>> aliases, String[] checkValues) {

        String[] modifiedValues = new String[checkValues.length];
        for (int i = 0; i < checkValues.length; i++) {
            // first, check if this is a fielded search
            String checkValue = checkValues[i];
            if (checkValue.contains(":")) {
                for (String psField : aliases.keySet()) {
                    if (checkValue.contains(psField + ":")) {
                        Pattern p = Pattern.compile("\\b" + psField + ":([\\w]+)\\b");
                        Matcher m = p.matcher(checkValue);
                        m.reset();
                        if (!m.find()) {
                            String[] fieldBits = checkValue.split(psField + ":");
                            String illegalField = "[Empty Field]";
                            if (fieldBits.length > 1) {
                                illegalField = fieldBits[1];
                                String[] illegalFieldBits = illegalField.split("\\s");
                                illegalField = illegalFieldBits[0];
                            }
                            String warning = "Collection \"" + illegalField + "\" is not well-formed; aliases may contain only alphanumberic characters and the \"_\" character.";
                            throw new SolrException(SolrException.ErrorCode.NOT_FOUND, warning);
                        }
                        m.reset();
                        while (m.find()) {
                            String all = checkValue.substring(m.start(), m.end());
                            String[] bits = all.split(":");
                            String collectionName = bits[1];
                            HashMap<String, String> themeAliases = aliases.get(psField);
                            if (themeAliases.containsKey(collectionName)) {
                                String fullQuery = themeAliases.get(collectionName);
                                checkValue = checkValue.replaceAll("\\b" + psField + ":" + collectionName + "\\b", fullQuery);
                            } else {
                                String msg = "Collection \"" + collectionName + "\" not defined in query_aliases.xml";
                                throw new SolrException(SolrException.ErrorCode.NOT_FOUND, msg);
                            }
                        }
                    }
                }
            }
            modifiedValues[i] = checkValue;
        }
        // if it *is* fielded, does it contain any of the pseudofield fields?
        // now we attempt a regex
        // check to make sure this is a fielded search
        // (i) does it contain a semicolon?

//        String fieldName = checkValue.split(":")[0].trim();
//        // (ii) is the semicolon not part of a search term (such as a URI)?
//        if (!fieldName.contains("\"") && !fieldName.contains("'")) {
//            // if so, expand
//            if (aliases.containsKey(fieldName)) {
//                HashMap<String, String> themeAliases = aliases.get(fieldName);
//                String collectionName = checkValue.split(":")[1].trim();
//                // let's also eliminate any possible confusion as to whether this is a phrase query or not
//                collectionName = collectionName.replace("\"", "");
//                collectionName = collectionName.replace("'", "");
//                if (themeAliases.containsKey(collectionName)) {
//                    checkValue = themeAliases.get(collectionName);
//                } else {
//                    String msg = "Collection \"" + collectionName + "\" not defined in query_aliases.xml";
//                    throw new SolrException(SolrException.ErrorCode.NOT_FOUND, msg);
//                }
//            }
//        }

        return modifiedValues;
    }

    private String outputParams(SolrParams params) {

        // for debugging purposes

        StringBuilder sb = new StringBuilder();
        Iterator<String> pit = params.getParameterNamesIterator();

        while (pit.hasNext()) {
            String pname = pit.next();
            sb.append(pname);
            sb.append(" :");
            String[] pvalues = params.getParams(pname);
            for (String pvalue : pvalues) {
                sb.append(pvalue);
                sb.append(" ");
            }
            sb.append("\n\n");
        }

        return sb.toString();

    }

    @Override
    public String getDescription() {
        String desc = "Expands keyword arguments and pseudofields into Solr-parseable queries";
        return desc;
    }
}