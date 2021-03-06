/*
 * Copyright 2009-2010 Kikini Limited and contributors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.kikini.logging.simpledb;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import com.amazonaws.services.simpledb.AmazonSimpleDB;
import com.amazonaws.services.simpledb.model.BatchPutAttributesRequest;
import com.amazonaws.services.simpledb.model.ReplaceableAttribute;
import com.amazonaws.services.simpledb.model.ReplaceableItem;

/**
 * Class to write the data represented in {@link SimpleDBRow} into SimpleDB.
 * <p>
 * This class will generate a random UUID for the "name" (item ID) of the row.
 * This is necessary since SimpleDB doesn't have the notion of an
 * auto-incrementing key.
 * <p>
 * The time column is written in ISO 8601 format, as recommended by Amazon,
 * which allows comparison and sorting.
 * 
 * @author Gabe Nell
 */
class SimpleDBWriter {

    private static final int MAX_ATTR_SIZE_BYTES = 1024;
    private static final int MAX_BATCH_PUT = 25;

    private static final String HOST_COLUMN = "host";
    private static final String CONTEXT_COLUMN = "context";
    private static final String TIME_COLUMN = "time";
    private static final String MESSAGE_COLUMN = "msg";
    private static final String LEVEL_COLUMN = "level";
    private static final String LOGGER_COLUMN = "logger";
    private static final String MDC_COLUMN_PREFIX = "mdc.";

    private DateTimeFormatter timeFormatter = ISODateTimeFormat.dateTime();
    private final AmazonSimpleDB sdb;
    private final String dom;

    SimpleDBWriter(AmazonSimpleDB sdb, String dom) {
        this.sdb = sdb;
        this.dom = dom;
    }

    private String formatTime(long time) {
        return timeFormatter.print(new DateTime(time));
    }

    /**
     * Set the time zone to use when writing the time column. The default is the
     * system time zone.
     * 
     * @param timeZone
     */
    public void setTimeZone(DateTimeZone timeZone) {
        timeFormatter = ISODateTimeFormat.dateTime().withZone(timeZone);
    }

    /**
     * We cannot easily and efficiently truncate to exactly 1024 bytes because
     * of the variable-length nature of UTF8. Instead, we'll start at 1024
     * characters (the common case) and back off in increments until we fit.
     * 
     * @param string
     * @return the truncated string
     */
    private String truncateToSize(String string) {
        // try to return as quickly as possible for the common case of the
        // string fitting
        Charset utf8 = Charset.forName("UTF-8");
        int size = string.getBytes(utf8).length;
        if (size <= MAX_ATTR_SIZE_BYTES) return string;

        // It's too big. Let's make it smaller. We pick a reasonable starting
        // point, which is either 1024 characters, which will work on the first
        // try for ASCII, or one decrement below the length of the string if it
        // is shorter than 1024 (and therefore not ASCII).
        final int decrement = 16;
        int nextIdx = MAX_ATTR_SIZE_BYTES >= string.length() ? string.length() - decrement : MAX_ATTR_SIZE_BYTES;
        do {
            string = string.substring(0, nextIdx);
            nextIdx -= decrement;
        } while (string.getBytes(utf8).length > MAX_ATTR_SIZE_BYTES);
        return string;
    }

    private void addIfNotNull(List<ReplaceableAttribute> atts, String key, String val) {
        if (val != null) {
            atts.add(new ReplaceableAttribute(key, truncateToSize(val), false));
        }
    }

    /**
     * Bulk-write the given rows to SimpleDB
     * 
     * @param rows
     */
    void writeRows(List<SimpleDBRow> rows) {
        if (rows.isEmpty()) return;

        List<SimpleDBRow> nextBatch;
        ListBatcher<SimpleDBRow> batchedList = new ListBatcher<SimpleDBRow>(rows, MAX_BATCH_PUT);

        while ((nextBatch = batchedList.nextBatch()) != null) {
            List<ReplaceableItem> items = new ArrayList<ReplaceableItem>();

            for (SimpleDBRow row : nextBatch) {
                List<ReplaceableAttribute> atts = new ArrayList<ReplaceableAttribute>();
                addIfNotNull(atts, HOST_COLUMN, row.getHost());
                addIfNotNull(atts, MESSAGE_COLUMN, row.getMsg());
                addIfNotNull(atts, LEVEL_COLUMN, row.getLevel());
                addIfNotNull(atts, LOGGER_COLUMN, row.getLogger());
                addIfNotNull(atts, CONTEXT_COLUMN, row.getContext());
                addIfNotNull(atts, TIME_COLUMN, formatTime(row.getTime()));

                for (Map.Entry<String, String> mdcProperty : row.getMDCPropertyMap().entrySet()) {
                    String mdcColumnName = MDC_COLUMN_PREFIX + mdcProperty.getKey();
                    addIfNotNull(atts, mdcColumnName, mdcProperty.getValue());
                }

                // SimpleDB will not generate a key for you, so we use a random
                // UUID as the key for this entry
                items.add(new ReplaceableItem(UUID.randomUUID().toString(), atts));
            }

            sdb.batchPutAttributes(new BatchPutAttributesRequest(dom, items));
        }
    }
}
