package org.apache.solr.handler.component;

import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.solr.core.AliasConfig;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.MultiMapSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Expands aliases into queries for thematic collections.
 *
 * Operates by taking apart the passed request, scanning it for thematic-collection keywords,
 * and replacing those it finds with the appropriate expanded query.
 *
 * @author thill
 * @author n.ireson@sheffield.ac.uk
 * @version 2018.07.28
 */
public class AliasingSearchHandler
        extends SearchHandler {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    // It is possible for the map to be accessed by different thread, thus use ConcurrentHashMap.
    private static final Map<SolrCore, AliasConfig> coreAliasConfigMap = new ConcurrentHashMap<>();

    public void init(NamedList params) {
        super.init(params);
    }

    @Override
    public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp)
            throws Exception {

        SolrCore core = req.getCore();
        AliasConfig aliasConfig = coreAliasConfigMap.get(core);
        if (aliasConfig == null) {
            try {
                // Note it is possible to use the init param to parameterise the AliasConfig constructor
                Path instanceDir = core.getCoreDescriptor().getInstanceDir();
                Path confDir = instanceDir.resolve("conf");
                aliasConfig = new AliasConfig(confDir, AliasConfig.DEFAULT_CONF_FILE, null);
                coreAliasConfigMap.put(core, aliasConfig);
            } catch (Exception e) {
                throw new SolrException(SolrException.ErrorCode.NOT_FOUND, "Alias config file not found", e);
            }
        }
        SolrParams params = req.getParams();
        Iterator<String> pnit = params.getParameterNamesIterator();
        Map<String, String[]> modifiedParams = new HashMap<String, String[]>();
        while (pnit.hasNext()) {
            String pname = pnit.next();
            String[] pvalues = params.getParams(pname);
            if (!pname.equals("q") && !pname.equals("fq")) {
                modifiedParams.put(pname, pvalues);
            } else {
                String[] modifiedValues = this.modifyValues(aliasConfig, pvalues);
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

    private String[] modifyValues(AliasConfig aliasConfig, String[] checkValues) {

        HashMap<String, HashMap<String, String>> aliases = aliasConfig.getAliases();
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
                            String warning = "Collection \"" + illegalField + "\" is not well-formed; " +
                                    "aliases may contain only alphanumberic characters and the \"_\" character.";
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
                                checkValue = checkValue.replaceAll(
                                        "\\b" + psField + ":" + collectionName + "\\b", fullQuery);
                            } else {
                                String msg = "Collection \"" + collectionName + "\" not defined in " +
                                        aliasConfig.getConfigFilename();
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


    // for debugging purposes
    private String outputParams(SolrParams params) {

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
        return "Expands keyword arguments and pseudofields into Solr-parseable queries";
    }
}