package com.example.enterpriseagent.intent.definition;

import com.example.enterpriseagent.intent.ConversationRecall;
import com.example.enterpriseagent.intent.IntentModel.Definition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 已知业务能力。目录顺序用于同分排序，高危动作在模型调用前判定。 */
public final class IntentCatalog {
    private static final String GENERIC_APPROVAL = "APPROVAL.CREATE";
    private static final Set<String> SPECIFIC_APPROVALS = Set.of(
            "LEAVE.CREATE", "OVERTIME.CREATE", "EXPENSE.CREATE", "PURCHASE.CREATE", "CONTRACT.APPROVAL", "CONTRACT.RENEW");
    private final List<Entry> entries = new ArrayList<>();

    public IntentCatalog() {
        add("CONTRACT.DELETE", "WORKFLOW", "contract.delete", "CRITICAL", "contracts.delete", "contractId", "", "删除|删掉", "合同");
        add("DATA.PURGE", "NONE", null, "CRITICAL", null, "", "", "清空|销毁|抹掉", "数据|记录");
        add("ACCOUNT.PASSWORD_CHANGE", "NONE", null, "HIGH", null, "", "", "改|修改|重置", "密码");
        add("APPROVAL.APPROVE", "WORKFLOW", "approval.approve", "HIGH", "approvals.act", "documentId", "", "批掉|批了|批一下|点通过|予以批准|同意审批", "");
        add("PAYROLL.QUERY", "DIRECT", "payroll.query", "HIGH", "payroll.read", "", "", "薪资|工资|薪酬", "明细|查|全公司");
        add("PII.EXPORT", "NONE", null, "CRITICAL", null, "", "", "导出|发群|拉取|全部|全员", "身份证号|银行卡号|手机号|通讯录");
        add("APPROVAL.DELEGATE", "WORKFLOW", "approval_skill.delegate", "HIGH", "approvals.delegate", "documentId,person", "", "转给|转办|委托", "待办|单子|审批|处理");
        add("APPROVAL.WITHDRAW", "WORKFLOW", "approval_skill.withdraw", "HIGH", "approvals.withdraw", "documentId", "", "撤回|撤销", "申请|审批");
        add("APPROVAL.URGE", "WORKFLOW", "approval_skill.urge", "NORMAL", null, "documentId", "person", "催一下|催办|催促", "单子|卡在|待办|审批");
        add("APPROVAL.PARALLEL", "WORKFLOW", "approval.parallel", "NORMAL", null, "contractId,departments", "", "会签|一起审|共同审核", "合同|法务|财务");
        add("APPROVAL.LIST", "DIRECT", "approval.list", "NORMAL", null, "", "approvalStatus", "查询|查|显示|列出", "待办|审批列表|审批记录|待处理审批");
        add("CONTRACT.LEGAL_REVIEW", "WORKFLOW", "approval_skill.transfer_legal", "NORMAL", null, "contractId", "", "法务", "审核|转给|审阅");
        add("CONTRACT.RENEW", "WORKFLOW", "approval.create_renew_approval", "NORMAL", null, "contractId", "", "续签|续约", "");
        add("CONTRACT.APPROVAL", "WORKFLOW", "approval.start_contract", "NORMAL", null, "contractId", "amount,departments", "发起|送审|走流程|提交", "合同");
        add("CONTRACT.PARSE", "DIRECT", "contract.parse", "NORMAL", null, "", "", "解析|提取|解读", "合同");
        add("CONTRACT.QUERY", "DIRECT", "contract.query_contract", "NORMAL", null, "", "contractId,period", "查|详情|信息|发我|列出", "合同");
        add("MEETING.BOOK", "WORKFLOW", "booking_skill.create_meeting", "NORMAL", null, "date,time,attendees", "floor", "订|预约|安排|拉个|找个", "会议室|开会|会议间|会场");
        add("EXPENSE.STATS", "WORKFLOW", "expense.statistics", "NORMAL", null, "period", "", "统计|汇总|盘点", "报销");
        add("EXPENSE.FILTER", "DIRECT", "expense.filter_overlimit", "NORMAL", null, "", "", "挑出|筛出|筛选|排查", "超标|超限|超额");
        add("EXPENSE.LIST", "DIRECT", null, "NORMAL", null, "", "reimbursementStatus", "查|显示|列出|有哪些", "我的报销单|报销列表|报销单列表|报销记录");
        add("EXPENSE.OA_SUBMIT", "DIRECT", null, "NORMAL", "oa.create", "oaTitle,oaCategory,oaExpenseAmount,oaExpenseDate,oaDescription",
                "", "提交|发起|创建", "oa\\s*报销");
        add("EXPENSE.QUERY", "DIRECT", "expense.query_expense", "NORMAL", null, "", "person,period,documentId", "查|进度|状态|报销单", "报销");
        add("EXPENSE.CREATE", "WORKFLOW", "approval_skill.create_expense", "NORMAL", null, "amount,reason,attachment", "documentId", "发起|提交|申请|报销", "报销");
        add("PURCHASE.CREATE", "WORKFLOW", "approval.create_purchase", "NORMAL", null, "amount", "reason", "发起|提交|采购申请|采购审批", "采购");
        add("LEAVE.CREATE", "WORKFLOW", "approval_skill.create_leave", "NORMAL", null, "duration,date,leaveType", "", "发起|提交|申请|请假|draft|发起一下", "请假|leave application|休假");
        add("OVERTIME.CREATE", "WORKFLOW", "approval_skill.create_overtime", "NORMAL", null, "date,duration", "person", "申请|发起|提交|加班单", "加班");
        add("ATTENDANCE.EXPORT", "DIRECT", "attendance.export", "NORMAL", null, "period", "person", "导出|下载", "考勤");
        add("ATTENDANCE.QUERY", "DIRECT", "attendance.query", "NORMAL", null, "", "person,period", "查|看|显示", "考勤");
        add("WEATHER.QUERY", "DIRECT", "weather_skill.query", "NORMAL", null, "city", "date", "天气|气温|预报", "");
        add("CALENDAR.LIST", "DIRECT", "calendar_skill.list", "NORMAL", null, "", "date,period", "查|看|列出|有啥|有哪些", "日程|日历|行程");
        add("MEETING.ROOM_LIST", "DIRECT", null, "NORMAL", null, "", "", "查|看|显示|列出|有哪些", "会议室");
        add("MEETING.DRAFT_CREATE", "DIRECT", null, "NORMAL", "oa.create", "oaTitle,oaAttendeeCount", "",
                "创建|建个|新建|生成", "会议(?:预约)?草稿");
        add("FLIGHT.BOOK", "WORKFLOW", "flight.book", "NORMAL", null, "city,date", "", "订|预订|买", "机票|航班");
        add("NOTIFICATION.REMIND", "WORKFLOW", "notice.notify", "NORMAL", null, "person", "date,topic", "提醒|催办|催他", "");
        add("EMAIL.SEND", "WORKFLOW", "mail.send", "NORMAL", null, "recipient", "", "邮件|email", "发|发送|寄");
        add("ORDER.LIST", "DIRECT", "orders_list", "NORMAL", null, "", "",
                "订单(?:列表|清单|一览|有哪些)|有哪些订单|(?:列出|显示)(?:一下)?(?:我(?:的)?|所有|全部)?订单|(?:查(?:查|看)?|看看)我的订单", "");
        add("ORDER.QUERY", "DIRECT", "orders_lookup", "NORMAL", null, "orderId", "", "查|详情|看看|状态", "订单");
        add("CUSTOMER.DELETE", "WORKFLOW", "customer.delete", "CRITICAL", "customers.delete", "customerId", "", "删除|删掉", "客户");
        add("CUSTOMER.QUERY", "DIRECT", null, "NORMAL", null, "customerId", "", "查|查看|了解", "客户");
        add("FINANCE.REPORT", "AGENT", "finance.report", "NORMAL", null, "period", "", "生成|分析|做|整理", "财务报表|财报");
        add("SALES.ANALYZE", "AGENT", null, "NORMAL", null, "", "period", "分析|解释|研究", "销售|销量");
        add("SALES.ANALYZE", 2, "AGENT", null, "NORMAL", null, "period", "", "分析|解释|研究", "销售|销量");
        add("APPROVAL.CREATE", "WORKFLOW", "approval.start", "NORMAL", null, "documentId", "amount", "发起|提交", "审批|流程");
        add("KNOWLEDGE.QUESTION", "KNOWLEDGE", null, "NORMAL", null, "", "", "是什么|有哪几种|是什么意思|怎么理解|是多少|有什么规定|多少种|上限|标准", "");
        add("CHAT.SMALLTALK", "CHAT", null, "NORMAL", null, "", "", "你好|您好|笑话|心情|真逗|哈哈|早上好|谢谢|在吗", "");
        add("CHAT.ASSISTANCE", "CHAT", null, "NORMAL", null, "", "", "", "");
        add("CHAT.FEEDBACK", "CHAT", null, "NORMAL", null, "", "", "太难用|气死|难用|很生气|体验太差|崩溃", "");
        add("SYSTEM.HELP", "NONE", null, "NORMAL", null, "", "", "能帮我做什么|你会什么|有什么能力|能力清单|怎么用|^/help$", "");
        add("SYSTEM.IDENTITY", "NONE", null, "NORMAL", null, "", "", "", "");
        add(ConversationRecall.INTENT, "NONE", null, "NORMAL", null, "", "", "", "");
        add(ConversationRecall.DOCUMENT_INTENT, "NONE", null, "NORMAL", null, "", "", "", "");
        add("SYSTEM.CLEAR", "NONE", null, "NORMAL", null, "", "", "清空上下文|重置对话|^/reset$", "");
        add("SYSTEM.CANCEL", "NONE", null, "NORMAL", null, "", "", "", "");
        add("SYSTEM.UNDO", "NONE", null, "NORMAL", null, "", "", "", "");
        add("SYSTEM.REPLAY", "NONE", null, "NORMAL", null, "", "", "", "");
        add("SYSTEM.CONFIRM", "NONE", null, "NORMAL", null, "", "", "", "");
        add("CONTRACT.UPLOAD", "WORKFLOW", "contract.upload", "NORMAL", null, "attachment", "contractId", "", "");
        add("SECURITY.BLOCKED", "NONE", null, "CRITICAL", null, "", "", "", "");
        add("UNKNOWN", "NONE", null, "NORMAL", null, "", "", "", "");
    }

    public Definition get(String id) {
        return entries.stream().map(Entry::definition).filter(definition -> definition.id().equals(id))
                .max(Comparator.comparingInt(Definition::version)).orElse(null);
    }

    public Definition get(String id, int version) {
        return entries.stream().map(Entry::definition)
                .filter(definition -> definition.id().equals(id) && definition.version() == version)
                .findFirst().orElse(null);
    }

    public Definition skill(String key) {
        return entries.stream().map(Entry::definition).filter(definition -> key.equals(definition.skill()))
                .findFirst().orElse(null);
    }

    public List<String> capabilities() {
        return entries.stream().map(Entry::definition).filter(definition -> definition.skill() != null)
                .map(Definition::id).toList();
    }

    public Map<String, String> routingDocuments() {
        var documents = new LinkedHashMap<String, String>();
        for (Entry entry : entries) {
            var def = entry.definition();
            if (entry.action() == null || !"NORMAL".equals(def.risk()) || def.version() != get(def.id()).version()) continue;
            String subject = entry.subject() == null ? "" : entry.subject().pattern().replace('|', ' ');
            documents.put(def.id(), def.id() + " " + entry.action().pattern().replace('|', ' ') + " " + subject);
        }
        return Map.copyOf(documents);
    }

    public List<Match> matches(String text) {
        var matches = new ArrayList<Match>();
        for (Entry entry : entries) {
            if (entry.action() == null || !entry.action().matcher(text).find()) continue;
            if (entry.subject() != null && !entry.subject().matcher(text).find()) continue;
            if (entry.definition().version() != get(entry.definition().id()).version()) continue;
            double score = entry.subject() == null ? 0.79 : 0.93;
            matches.add(new Match(entry.definition(), score));
        }
        boolean specificApproval = hasSpecificApproval(matches);
        return matches.stream().filter(match -> !specificApproval || !GENERIC_APPROVAL.equals(match.definition().id()))
                .sorted(Comparator.comparingDouble(Match::score).reversed()).toList();
    }

    /** 专用动作已经被规则识别时，避免召回把泛化的发起审批候选重新加回来。 */
    public boolean allowsRecalledIntent(String id, List<Match> rules) {
        return !GENERIC_APPROVAL.equals(id) || !hasSpecificApproval(rules);
    }

    private static boolean hasSpecificApproval(List<Match> matches) {
        return matches.stream().anyMatch(match -> SPECIFIC_APPROVALS.contains(match.definition().id()));
    }

    /** 业务对象是澄清线索，不能单独决定要执行哪个动作。 */
    public boolean recognizesSubject(String text) {
        return entries.stream().anyMatch(entry -> entry.subject() != null && entry.subject().matcher(text).find());
    }

    private void add(String id, String route, String skill, String risk, String scope, String required,
                     String optional, String action, String subject) {
        add(id, 1, route, skill, risk, scope, required, optional, action, subject);
    }

    private void add(String id, int version, String route, String skill, String risk, String scope, String required,
                     String optional, String action, String subject) {
        entries.add(new Entry(new Definition(id, version, route, skill, risk, scope, fields(required), fields(optional)),
                regex(action), regex(subject)));
    }

    private static List<String> fields(String value) { return value.isEmpty() ? List.of() : List.of(value.split(",")); }
    private static Pattern regex(String value) {
        return value.isEmpty() ? null : Pattern.compile(value, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private record Entry(Definition definition, Pattern action, Pattern subject) {}
    public record Match(Definition definition, double score) {}
}
