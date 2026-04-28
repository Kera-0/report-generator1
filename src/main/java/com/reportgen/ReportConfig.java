package com.reportgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportConfig {
    public String title;
    public List<SourceConfig> sources = new ArrayList<>();
    public List<TableConfig> tables = new ArrayList<>();
    public Map<String, Object> context = new LinkedHashMap<>();
    public List<LayoutItem> layout = new ArrayList<>();
}
