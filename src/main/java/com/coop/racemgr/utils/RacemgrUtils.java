package com.coop.racemgr.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.PageRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.*;

public class RacemgrUtils {
    private static final Logger logger = LogManager.getLogger(RacemgrUtils.class);

    // Shared mapper. Parsing is lenient about syntax (the dedicated server's files are JSON-ish)
    // but unknown properties still fail, and untyped integers stay Long as they were with json-simple.
    public static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .build();

    public static List filterList(List incomingLIst, int count, boolean random) {
        List filteredList;
        if (random) {
            filteredList = RacemgrUtils.getRandomElement(incomingLIst, count);
        } else {
            filteredList = incomingLIst.subList(0, count);
        }
        return filteredList;
    }

    public static ArrayList getRandomElement(List list, int totalItems) {
        int listSize = list.size();
        int count = totalItems > listSize ? listSize : totalItems;

        Random rand = new Random();
        List newList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Random index between 0 to size of given List
            int randomIndex = rand.nextInt(list.size());
            newList.add(list.get(randomIndex));
            list.remove(randomIndex); //Don't select again
        }

        return (ArrayList) newList;
    }

    // NOTE: This does read the whole file into memory, fine for our small files
    public static void replaceStringInFile(File file, String search, String replace) {
        try {
            FileReader fr = new FileReader(file);
            String s;
            String totalStr = "";
            try (BufferedReader br = new BufferedReader(fr)) {
                while ((s = br.readLine()) != null) {
                    totalStr += s + "\r\n";
                }
                totalStr = totalStr.replaceAll(search, replace);
                FileWriter fw = new FileWriter(file);
                fw.write(totalStr);
                fw.close();
            }
        } catch(Exception e){
            e.printStackTrace();
        }
    }

    public static String getValueFromObj(String key, Object obj, String defaultValue) {
        String val;
        try {
            val = RacemgrUtils.getValueFromObj(key, obj);
            if (val == null || val.trim().equals("")) {
                val = defaultValue;
            }
        } catch (Exception e) {
            val = defaultValue;
        }
        return val;
    }

    public static String getValueFromObj(String key, Object obj) {
        String val = null;
        try {
            String objStr = JSON.writeValueAsString(obj);
            ObjectNode node = JSON.readValue(objStr, ObjectNode.class);
            if (node != null && node.get(key) != null) {
                val = node.get(key).textValue();
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        return val;
    }

    // Parses JSON into plain Maps/Lists. Returns null when the text isn't valid JSON.
    public static Object jsonStrToObj(String jsonString) {
        try {
            return JSON.readValue(jsonString, Object.class);
        } catch (JsonProcessingException e) {
            logger.warn("Could not parse JSON: " + e.getOriginalMessage());
            return null;
        }
    }

    public static void wait(int ms) {
        try {
            Thread.sleep(ms);
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public static String getCurrentDateTime() {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd 'at' HH:mm:ss z");
        Date date = new Date(System.currentTimeMillis());
        return formatter.format(date);
    }

    // Build a PageRequest from client-supplied values without letting a caller request
    // negative pages or unbounded result sets.
    public static PageRequest boundedPageRequest(Integer page, Integer limit, int maxLimit) {
        int safePage = page == null ? 0 : Math.max(0, page);
        int safeLimit = limit == null ? 10 : Math.min(Math.max(1, limit), maxLimit);
        return PageRequest.of(safePage, safeLimit);
    }
}
