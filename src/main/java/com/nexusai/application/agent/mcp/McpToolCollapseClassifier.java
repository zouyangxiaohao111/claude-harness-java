package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.tool.Tool;

import java.util.Set;

/**
 * MCP 工具折叠分类器 · 移植 CC {@code classifyForCollapse.ts}
 * （{@code Open-ClaudeCode/src/tools/MCPTool/classifyForCollapse.ts}）。
 *
 * <p>CC 真源 (grep 自验): {@code classifyMcpToolForCollapse(_serverName, toolName)}
 * （classifyForCollapse.ts:595-610）对 toolName 做 normalize（camelCase/kebab → snake_case）
 * 后查 SEARCH_TOOLS / READ_TOOLS allowlist，返回 {@code {isSearch, isRead}}。
 * Java 端映射到 {@link Tool.SearchReadKind} 4 态（NONE / IS_READ / IS_SEARCH / IS_LIST）。
 *
 * <p><b>WHY 单独成类</b>：allowlist 近 500 项，独立类避免污染 {@code McpServerTool}
 * 可读性，且未来 OPD 若把 {@code searchReadKind} 提到 Tool 接口默认实现，可被多工具复用。
 *
 * <p><b>去重说明</b>：CC 源 allowlist 个别工具名出现多次（如 search_docs / find_projects），
 * JS {@code Set} 自动去重；Java {@code Set.of} 遇重复抛 IllegalArgumentException → 生成时去重（保序），
 * 与 JS Set 语义一致。
 *
 * <h2>CC original 行号</h2>
 * <ul>
 *   <li>{@code normalize}: classifyForCollapse.ts:586-592</li>
 *   <li>{@code classifyMcpToolForCollapse}: classifyForCollapse.ts:595-610</li>
 *   <li>{@code SEARCH_TOOLS}: classifyForCollapse.ts:14-141</li>
 *   <li>{@code READ_TOOLS}: classifyForCollapse.ts:142-590</li>
 * </ul>
 */
final class McpToolCollapseClassifier {

    private McpToolCollapseClassifier() {
    }

    /** CC original: SEARCH_TOOLS (classifyForCollapse.ts:14-141)。 */
    private static final Set<String> SEARCH_TOOLS = Set.of(
        "slack_search_public", "slack_search_public_and_private", "slack_search_channels", "slack_search_users",
        "search_code", "search_repositories", "search_issues", "search_pull_requests",
        "search_orgs", "search_users", "search_documentation", "search_logs",
        "search_spans", "search_rum_events", "search_audit_logs", "search_monitors",
        "search_monitor_groups", "find_slow_spans", "find_monitors_matching_pattern", "search_docs",
        "search_events", "search_issue_events", "find_organizations", "find_teams",
        "find_projects", "find_releases", "find_dsns", "search",
        "gmail_search_messages", "google_drive_search", "gcal_find_my_free_time", "gcal_find_meeting_times",
        "gcal_find_user_emails", "search_jira_issues_using_jql", "search_confluence_using_cql", "lookup_jira_account_id",
        "confluence_search", "jira_search", "jira_search_fields", "asana_search_tasks",
        "asana_typeahead_search", "search_files", "search_nodes", "brave_web_search",
        "brave_local_search", "search_dashboards", "search_folders", "search_stripe_resources",
        "search_stripe_documentation", "search_articles", "find_related_articles", "lookup_article_by_citation",
        "search_papers", "search_pubmed", "search_pubmed_key_words", "search_pubmed_advanced",
        "pubmed_search", "pubmed_mesh_lookup", "firecrawl_search", "web_search_exa",
        "web_search_advanced_exa", "people_search_exa", "linkedin_search_exa", "deep_search_exa",
        "perplexity_search", "perplexity_search_web", "tavily_search", "obsidian_simple_search",
        "obsidian_complex_search", "find", "search_knowledge", "search_memories",
        "find_memories_by_name", "search_records", "find_tasks", "find_tasks_by_date",
        "find_completed_tasks", "find_sections", "find_comments", "find_project_collaborators",
        "find_activity", "find_labels", "find_filters", "search_catalog",
        "search_modules", "search_providers", "search_policies"
    );

    /** CC original: READ_TOOLS (classifyForCollapse.ts:142-590)。 */
    private static final Set<String> READ_TOOLS = Set.of(
        "slack_read_channel", "slack_read_thread", "slack_read_canvas", "slack_read_user_profile",
        "slack_list_channels", "slack_get_channel_history", "slack_get_thread_replies", "slack_get_users",
        "slack_get_user_profile", "get_me", "get_team_members", "get_teams",
        "get_commit", "get_file_contents", "get_repository_tree", "list_branches",
        "list_commits", "list_releases", "list_tags", "get_latest_release",
        "get_release_by_tag", "get_tag", "list_issues", "issue_read",
        "list_issue_types", "get_label", "list_label", "pull_request_read",
        "get_gist", "list_gists", "list_notifications", "get_notification_details",
        "projects_list", "projects_get", "actions_get", "actions_list",
        "get_job_logs", "get_code_scanning_alert", "list_code_scanning_alerts", "get_dependabot_alert",
        "list_dependabot_alerts", "get_secret_scanning_alert", "list_secret_scanning_alerts", "get_global_security_advisory",
        "list_global_security_advisories", "list_org_repository_security_advisories", "list_repository_security_advisories", "get_discussion",
        "get_discussion_comments", "list_discussion_categories", "list_discussions", "list_starred_repositories",
        "get_issue", "get_pull_request", "list_pull_requests", "get_pull_request_files",
        "get_pull_request_status", "get_pull_request_comments", "get_pull_request_reviews", "list_comments",
        "list_cycles", "get_document", "list_documents", "list_issue_statuses",
        "get_issue_status", "list_my_issues", "list_issue_labels", "list_projects",
        "get_project", "list_project_labels", "list_teams", "get_team",
        "list_users", "get_user", "aggregate_logs", "list_spans",
        "aggregate_spans", "analyze_trace", "trace_critical_path", "query_metrics",
        "aggregate_rum_events", "list_rum_metrics", "get_rum_metric", "list_monitors",
        "get_monitor", "check_can_delete_monitor", "validate_monitor", "validate_existing_monitor",
        "list_dashboards", "get_dashboard", "query_dashboard_widget", "list_notebooks",
        "get_notebook", "query_notebook_cell", "get_profiling_metrics", "compare_profiling_metrics",
        "whoami", "get_issue_details", "get_issue_tag_values", "get_trace_details",
        "get_event_attachment", "get_doc", "get_sentry_resource", "list_events",
        "list_issue_events", "get_sentry_issue", "fetch", "get_comments",
        "get_users", "get_self", "gmail_get_profile", "gmail_read_message",
        "gmail_read_thread", "gmail_list_drafts", "gmail_list_labels", "google_drive_fetch",
        "google_drive_export", "gcal_list_calendars", "gcal_list_events", "gcal_get_event",
        "atlassian_user_info", "get_accessible_atlassian_resources", "get_visible_jira_projects", "get_jira_project_issue_types_metadata",
        "get_jira_issue", "get_transitions_for_jira_issue", "get_jira_issue_remote_issue_links", "get_confluence_spaces",
        "get_confluence_page", "get_pages_in_confluence_space", "get_confluence_page_ancestors", "get_confluence_page_descendants",
        "get_confluence_page_footer_comments", "get_confluence_page_inline_comments", "confluence_get_page", "confluence_get_page_children",
        "confluence_get_comments", "confluence_get_labels", "jira_get_issue", "jira_get_transitions",
        "jira_get_worklog", "jira_get_agile_boards", "jira_get_board_issues", "jira_get_sprints_from_board",
        "jira_get_sprint_issues", "jira_get_link_types", "jira_download_attachments", "jira_batch_get_changelogs",
        "jira_get_user_profile", "jira_get_project_issues", "jira_get_project_versions", "asana_get_attachment",
        "asana_get_attachments_for_object", "asana_get_goal", "asana_get_goals", "asana_get_parent_goals_for_goal",
        "asana_get_portfolio", "asana_get_portfolios", "asana_get_items_for_portfolio", "asana_get_project",
        "asana_get_projects", "asana_get_project_sections", "asana_get_project_status", "asana_get_project_statuses",
        "asana_get_project_task_counts", "asana_get_projects_for_team", "asana_get_projects_for_workspace", "asana_get_task",
        "asana_get_tasks", "asana_get_stories_for_task", "asana_get_teams_for_workspace", "asana_get_teams_for_user",
        "asana_get_team_users", "asana_get_time_period", "asana_get_time_periods", "asana_get_user",
        "asana_get_workspace_users", "asana_list_workspaces", "read_file", "read_text_file",
        "read_media_file", "read_multiple_files", "list_directory", "list_directory_with_sizes",
        "directory_tree", "get_file_info", "list_allowed_directories", "read_graph",
        "open_nodes", "query", "read_query", "list_tables",
        "describe_table", "git_status", "git_diff", "git_diff_unstaged",
        "git_diff_staged", "git_log", "git_show", "git_branch",
        "list_users_by_org", "get_dashboard_by_uid", "get_dashboard_summary", "get_dashboard_property",
        "get_dashboard_panel_queries", "run_panel_query", "list_datasources", "get_datasource",
        "get_query_examples", "query_prometheus", "query_prometheus_histogram", "list_prometheus_metric_metadata",
        "list_prometheus_metric_names", "list_prometheus_label_names", "list_prometheus_label_values", "query_loki_logs",
        "query_loki_stats", "query_loki_patterns", "list_loki_label_names", "list_loki_label_values",
        "list_incidents", "get_incident", "list_sift_investigations", "get_sift_investigation",
        "get_sift_analysis", "list_oncall_schedules", "get_oncall_shift", "get_current_oncall_users",
        "list_oncall_teams", "list_oncall_users", "list_alert_groups", "get_alert_group",
        "get_annotations", "get_annotation_tags", "get_panel_image", "get_outlier_incident",
        "get_past_incidents", "get_related_incidents", "list_incident_notes", "list_incident_workflows",
        "get_incident_workflow", "list_services", "get_service", "list_team_members",
        "get_user_data", "list_schedules", "get_schedule", "list_schedule_users",
        "list_oncalls", "list_log_entries", "get_log_entry", "list_escalation_policies",
        "get_escalation_policy", "list_event_orchestrations", "get_event_orchestration", "list_status_pages",
        "get_status_page_post", "list_alerts_from_incident", "get_alert_from_incident", "list_change_events",
        "get_change_event", "list_organizations", "get_organization", "get_cost",
        "list_extensions", "list_migrations", "get_logs", "get_advisors",
        "get_project_url", "get_publishable_keys", "generate_typescript_types", "list_edge_functions",
        "get_edge_function", "list_storage_buckets", "get_storage_config", "get_stripe_account_info",
        "retrieve_balance", "list_customers", "list_products", "list_prices",
        "list_invoices", "list_payment_intents", "list_subscriptions", "list_coupons",
        "list_disputes", "fetch_stripe_resources", "get_article_metadata", "get_full_text_article",
        "convert_article_ids", "get_copyright_status", "download_paper", "list_papers",
        "read_paper", "get_paper_fulltext", "get_pubmed_article_metadata", "download_pubmed_pdf",
        "pubmed_fetch", "pubmed_pmc_fetch", "pubmed_spell", "pubmed_cite",
        "pubmed_related", "bigquery_query", "bigquery_schema", "list_dataset_ids",
        "list_table_ids", "get_dataset_info", "get_table_info", "firecrawl_scrape",
        "firecrawl_map", "firecrawl_crawl", "firecrawl_check_crawl_status", "firecrawl_extract",
        "get_code_context_exa", "company_research_exa", "crawling_exa", "deep_researcher_check",
        "perplexity_ask", "perplexity_research", "perplexity_reason", "tavily_extract",
        "tavily_crawl", "tavily_map", "tavily_research", "obsidian_list_files_in_vault",
        "obsidian_list_files_in_dir", "obsidian_get_file_contents", "obsidian_batch_get_file_contents", "obsidian_get_periodic_note",
        "obsidian_get_recent_periodic_notes", "obsidian_get_recent_changes", "get_figma_data", "download_figma_images",
        "browser_console_messages", "browser_network_requests", "browser_take_screenshot", "browser_snapshot",
        "browser_get_config", "browser_route_list", "browser_cookie_list", "browser_cookie_get",
        "browser_localstorage_list", "browser_localstorage_get", "browser_sessionstorage_list", "browser_sessionstorage_get",
        "browser_storage_state", "puppeteer_screenshot", "list_databases", "list_collections",
        "collection_indexes", "collection_schema", "collection_storage_size", "db_stats",
        "explain", "mongodb_logs", "aggregate", "count",
        "export", "get_neo4j_schema", "read_neo4j_cypher", "list_instances",
        "get_instance_details", "get_instance_by_name", "list_indices", "get_mappings",
        "esql", "get_shards", "list_records", "list_bases",
        "get_record", "get_productivity_stats", "get_overview", "fetch_object",
        "user_info", "list_workspaces", "view_attachment", "get_available_services",
        "read_documentation", "read_sections", "recommend", "analyze_log_group",
        "analyze_metric", "describe_log_groups", "get_active_alarms", "get_alarm_history",
        "get_metric_data", "get_metric_metadata", "kubectl_get", "kubectl_describe",
        "kubectl_logs", "kubectl_context", "explain_resource", "list_api_resources",
        "namespaces_list", "nodes_log", "nodes_top", "pods_get",
        "pods_list", "pods_list_in_namespace", "pods_log", "pods_top",
        "resources_get", "resources_list"
    );

    /**
     * CC original: normalize (classifyForCollapse.ts:586-592)。
     * <pre>
     * name.replace(/([a-z])([A-Z])/g, '$1_$2').replace(/-/g, '_').toLowerCase()
     * </pre>
     */
    private static String normalize(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2")
            .replace('-', '_')
            .toLowerCase();
    }

    /**
     * CC original: classifyMcpToolForCollapse (classifyForCollapse.ts:595-610)。
     * serverName CC 侧为占位（不参与分类），Java 端忽略以对齐签名。
     *
     * @param serverName MCP server 名（CC 侧忽略）
     * @param toolName   MCP server 上的 tool 名
     * @return IS_SEARCH（命中 SEARCH_TOOLS）/ IS_READ（命中 READ_TOOLS）/ NONE
     */
    static Tool.SearchReadKind classify(String serverName, String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return Tool.SearchReadKind.NONE;
        }
        String normalized = normalize(toolName);
        if (SEARCH_TOOLS.contains(normalized)) {
            return Tool.SearchReadKind.IS_SEARCH;
        }
        if (READ_TOOLS.contains(normalized)) {
            return Tool.SearchReadKind.IS_READ;
        }
        return Tool.SearchReadKind.NONE;
    }
}
