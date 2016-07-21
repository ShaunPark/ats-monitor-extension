/**
 * Copyright 2016 Solulink co ltd.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kr.co.solulink.appdynamics.extension;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.json.JSONObject;

import com.appdynamics.extensions.ArgumentsValidator;
import com.appdynamics.extensions.PathResolver;
import com.appdynamics.extensions.http.Response;
import com.appdynamics.extensions.http.SimpleHttpClient;
import com.appdynamics.extensions.http.UrlBuilder;
import com.google.common.base.Strings;
import com.singularity.ee.agent.systemagent.api.AManagedMonitor;
import com.singularity.ee.agent.systemagent.api.MetricWriter;
import com.singularity.ee.agent.systemagent.api.TaskExecutionContext;
import com.singularity.ee.agent.systemagent.api.TaskOutput;
import com.singularity.ee.agent.systemagent.api.exception.TaskExecutionException;
/**
 * ATSMonitor is a class that provides metrics on Apache Traffic server by using the
 * ATSMonitor status stub.
 */
public class ATSMonitor extends AManagedMonitor  {
    /**
     * The metric can be found in Application Infrastructure Performance|{@literal <}Node{@literal >}|Custom Metrics|CacheServer|ATS
     */
    private static final Logger logger = Logger.getLogger(ATSMonitor.class);

    private static final String METRIC_SEPARATOR = "|";

    private static final Map<String, String> DEFAULT_ARGS = new HashMap<String, String>() {/**
		 * 
		 */
		private static final long serialVersionUID = 1L;

	{
        put("host", "localhost");
        put("port", "8080");
        put("location", "_stats");
        put("metric-prefix", "Custom Metrics|CacheServer|ATS");
    }};
    
    private static final String FILE_NAME = "monitors/ATSMonitor/metrics.properties";
    private final Properties targetMetrics = new Properties();
    private int sendCount = 0;
    
    public ATSMonitor() {
        String version = getClass().getPackage().getImplementationTitle();
        String msg = String.format("Using Monitor Version [%s]", version);
        logger.info(msg);
        System.out.println(msg);
		try {
			targetMetrics.load(new FileInputStream(getConfigFilename(FILE_NAME)));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }

    private String getConfigFilename(String filename) {
        // for absolute paths
        if (new File(filename).exists()) {
            return filename;
        }
        // for relative paths
        File jarPath = PathResolver.resolveDirectory(ATSMonitor.class);
        String configFileName = "";
        if (!Strings.isNullOrEmpty(filename)) {
            configFileName = jarPath + File.separator + filename;
        }
        return configFileName;
    }
    
    /**
     * Main execution method that uploads the metrics to the AppDynamics Controller
     *
     * @see com.singularity.ee.agent.systemagent.api.ITask#execute(java.util.Map, com.singularity.ee.agent.systemagent.api.TaskExecutionContext)
     */
    public TaskOutput execute(Map<String, String> argsMap, TaskExecutionContext executionContext)
            throws TaskExecutionException {
        try {

            logger.debug("The args map before filling the default is:  " + argsMap);
            argsMap = ArgumentsValidator.validateArguments(argsMap, DEFAULT_ARGS);
            logger.debug("The args map after filling the default is: " + argsMap);

            Map<String, String> resultMap = populate(argsMap);

            String metricPrefix = argsMap.get("metric-prefix");
            
            logger.info("ATS Metric Upload start");

            printAllMetrics(metricPrefix, resultMap);

            logger.info("ATS Metric Upload Complete - " + sendCount + " metrics are sended.");
            return new TaskOutput("ATS Metric Upload Complete");
        } catch (Exception e) {
            logger.error("Error: " + e);
            return new TaskOutput("Error: " + e);
        }
    }

    /**
     * Fetches Statistics from ATS Server
     *
     * @param argsMap arguments passed
     */
    private Map<String, String> populate(Map<String, String> argsMap) throws IOException, TaskExecutionException {

        SimpleHttpClient httpClient = SimpleHttpClient.builder(argsMap).build();
        try {
            String url = UrlBuilder.builder(argsMap).path(argsMap.get("location")).build();
            Response response = httpClient.target(url).get();
            String responseBody = response.string();
            String header = response.getHeader("Content-Type");

            Map<String, String> resultMap = null;
            if (header != null && header.contains("text/javascript")) {
                resultMap = parsePlusStatsResult(responseBody);
            } else {
                logger.error("Invalid content type [ " + header + " ] for URL " + url);
                throw new TaskExecutionException("Invalid content type [ " + header + " ] for URL " + url);
            }
            return resultMap;
        } finally {
            httpClient.close();
        }
    }

    private Map<String, String> parsePlusStatsResult(String responseBody) {
        Map<String, String> resultMap = new HashMap<String, String>();
        JSONObject jsonObject = new JSONObject(responseBody);  
        JSONObject jobj = (JSONObject) jsonObject.get("global");

        for ( Object key : jobj.keySet()) {
        	String keystr = key.toString();
        	String value = (String)jobj.get(keystr);
        	try {        		
        		Double longVal = Double.parseDouble(value);
        		resultMap.put(keystr, "" + toWholeNumberString(longVal));
        	} catch(NumberFormatException e) {
        		logger.error("Skip   " + value);
        	}
      	}
        return resultMap;
    }
    
    private String toWholeNumberString(Double value) {
    	return value > 0 && value < 1.0d ? "1" : String.valueOf(Math.round(value));
    }
    
    private String toADStr(String str) {
		 return str.replaceAll("\\.","|").replaceAll("_"," ");
	}

    /**
     * @param metricPrefix
     * @param resultMap
     */
    private void printAllMetrics(String metricPrefix, Map<String, String> resultMap) {
    	sendCount = 0;
        for (Map.Entry<String, String> metricEntry : resultMap.entrySet()) {        	
            printMetric(metricPrefix, metricEntry.getKey(), metricEntry.getValue());
            sendCount++;
        }
    }

    /**
     * Returns the metric to the AppDynamics Controller.
     *
     * @param metricPrefix Metric prefix
     * @param metricName   Name of the Metric
     * @param metricValue  Value of the Metric
     */
    private void printMetric(String metricPrefix, String metricName, Object metricValue) {
    	String metricProp = targetMetrics.getProperty(metricName);
    	if( metricProp != null ) {
    		String[] propStrs = metricProp.split(":");

    		String aggregation = MetricWriter.METRIC_AGGREGATION_TYPE_OBSERVATION;
	        String timeRollup = MetricWriter.METRIC_TIME_ROLLUP_TYPE_AVERAGE;

    		if( propStrs[0].startsWith("A")) {
    			aggregation = MetricWriter.METRIC_AGGREGATION_TYPE_AVERAGE;
    		} else if( propStrs[0].startsWith("S")) {
    			aggregation = MetricWriter.METRIC_AGGREGATION_TYPE_SUM;
    		}
    		
    		if( propStrs[1].startsWith("C")) {
    			aggregation = MetricWriter.METRIC_TIME_ROLLUP_TYPE_CURRENT;
    		} else if( propStrs[1].startsWith("S")) {
    			aggregation = MetricWriter.METRIC_TIME_ROLLUP_TYPE_SUM;
    		}
    		
	        String cluster = MetricWriter.METRIC_CLUSTER_ROLLUP_TYPE_COLLECTIVE;
	
	        String metricPath = metricPrefix + METRIC_SEPARATOR + toADStr(metricName);
	
	        MetricWriter metricWriter = getMetricWriter(metricPath,
	                aggregation,
	                timeRollup,
	                cluster
	        );
	        metricWriter.printMetric(String.valueOf(metricValue));
	
	        if (logger.isDebugEnabled()) {
	            logger.debug("Metric [" + aggregation + "/" + timeRollup + "/" + cluster
	                    + "] metric = " + metricPath + " = " + metricValue);
	        }
    	} 
    }
}