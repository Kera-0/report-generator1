package com.reportgen;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class DataTable {
    final String id;
    final List<String> headers;
    final List<Map<String, Object>> rows;

    DataTable(String id, List<String> headers, List<Map<String, Object>> rows) {
        this.id = id;
        this.headers = List.copyOf(headers);
        this.rows = rows.stream().map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
    }

    List<Object> column(String name) {
        if (!headers.contains(name)) {
            throw new IllegalArgumentException("Unknown column '" + name + "' in table '" + id + "'");
        }
        return rows.stream().map(row -> row.get(name)).toList();
    }
}
