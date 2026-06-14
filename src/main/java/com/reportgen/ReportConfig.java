package com.reportgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportConfig {
    public String title;
    public String template;
    public List<SourceConfig> sources = new ArrayList<>();
    public List<TableConfig> tables = new ArrayList<>();
    public Map<String, FunctionConfig> functions = new LinkedHashMap<>();
    public Map<String, Object> context = new LinkedHashMap<>();
    public List<LayoutItem> layout = new ArrayList<>();
}
