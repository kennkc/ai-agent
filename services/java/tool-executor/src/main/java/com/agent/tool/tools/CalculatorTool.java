package com.agent.tool.tools;

import com.agent.tool.model.ToolContext;
import com.agent.tool.model.ToolHandler;
import com.agent.tool.model.ToolOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计算器工具（R5-04）。
 *
 * <p><b>不使用任何 eval / 脚本引擎</b>——手写递归下降解析器。理由：计算器是 LLM 最常触发的
 * 工具，一旦走 `eval` 或 `nashorn`，等于把"参数校验"这道闸整个绕过（表达式本身就是代码）。
 * 本实现只认白名单运算符与函数，其余字符一律词法报错。
 *
 * <p>支持：{@code + - * / % ^}、括号、一元正负、以及
 * {@code sqrt abs min max round floor ceil pow sin cos tan log log10 exp}。
 */
@Component
public class CalculatorTool implements ToolHandler {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "expr": { "type": "string", "minLength": 1, "maxLength": 512,
                          "description": "算术表达式，例如 128*17 或 sqrt(2)+pow(2,10)" }
              },
              "required": ["expr"],
              "additionalProperties": false
            }""";

    private final String name = "calculator";

    public static String schema() { return SCHEMA; }

    @Override public String name() { return name; }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
        Object raw = arguments == null ? null : arguments.get("expr");
        if (raw == null) return ToolOutcome.fail("AGENT_TOOL_ARGS_INVALID", "缺少参数 expr");
        String expression = String.valueOf(raw);
        try {
            double value = new Parser(expression).parse();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("expr", expression);
            data.put("value", value);
            data.put("integer", isIntegral(value));
            if (isIntegral(value)) data.put("int_value", (long) value);
            return ToolOutcome.ok(format(value), data);
        } catch (ArithmeticException e) {
            return ToolOutcome.fail("AGENT_TOOL_EXEC_FAILED", "算术错误：" + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolOutcome.fail("AGENT_TOOL_ARGS_INVALID", "表达式不合法：" + e.getMessage());
        }
    }

    private static boolean isIntegral(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value == Math.rint(value);
    }

    private static String format(double value) {
        if (isIntegral(value)) return String.valueOf((long) value);
        return String.valueOf(value);
    }

    /** 递归下降解析器（无反射、无脚本引擎，纯算术） */
    static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) { this.text = text == null ? "" : text; }

        double parse() {
            if (text.isBlank()) throw new IllegalArgumentException("空表达式");
            double value = expr();
            skipSpaces();
            if (pos < text.length()) {
                throw new IllegalArgumentException("位置 " + pos + " 存在无法解析的字符 '" + text.charAt(pos) + "'");
            }
            return value;
        }

        private double expr() {
            double value = term();
            while (true) {
                skipSpaces();
                if (eat('+')) value += term();
                else if (eat('-')) value -= term();
                else return value;
            }
        }

        private double term() {
            double value = unary();
            while (true) {
                skipSpaces();
                if (eat('*')) value *= unary();
                else if (eat('/')) {
                    double divisor = unary();
                    if (divisor == 0) throw new ArithmeticException("除以零");
                    value /= divisor;
                } else if (eat('%')) {
                    double divisor = unary();
                    if (divisor == 0) throw new ArithmeticException("取模零");
                    value %= divisor;
                } else return value;
            }
        }

        private double unary() {
            skipSpaces();
            if (eat('-')) return -unary();
            if (eat('+')) return unary();
            return power();
        }

        private double power() {
            double base = primary();
            skipSpaces();
            if (eat('^')) {
                double exponent = unary();     // 右结合
                return Math.pow(base, exponent);
            }
            return base;
        }

        private double primary() {
            skipSpaces();
            if (pos >= text.length()) throw new IllegalArgumentException("表达式意外结束");
            char ch = text.charAt(pos);
            if (ch == '(') {
                pos++;
                double value = expr();
                skipSpaces();
                if (!eat(')')) throw new IllegalArgumentException("缺少右括号");
                return value;
            }
            if (Character.isDigit(ch) || ch == '.') return number();
            if (Character.isLetter(ch)) return function();
            throw new IllegalArgumentException("位置 " + pos + " 出现非法字符 '" + ch + "'");
        }

        private double number() {
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) pos++;
            String literal = text.substring(start, pos);
            try {
                return Double.parseDouble(literal);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非法数字 " + literal);
            }
        }

        private double function() {
            int start = pos;
            while (pos < text.length() && Character.isLetterOrDigit(text.charAt(pos))) pos++;
            String funcName = text.substring(start, pos).toLowerCase();
            skipSpaces();
            if (!eat('(')) throw new IllegalArgumentException("函数 " + funcName + " 缺少左括号");

            List<Double> args = new ArrayList<>();
            skipSpaces();
            if (!eat(')')) {
                args.add(expr());
                skipSpaces();
                while (eat(',')) {
                    args.add(expr());
                    skipSpaces();
                }
                if (!eat(')')) throw new IllegalArgumentException("函数 " + funcName + " 缺少右括号");
            }
            return applyFunction(funcName, args);
        }

        private double applyFunction(String funcName, List<Double> args) {
            switch (funcName) {
                case "sqrt":
                    require(funcName, args, 1);
                    if (args.get(0) < 0) throw new ArithmeticException("sqrt 负数");
                    return Math.sqrt(args.get(0));
                case "abs":
                    require(funcName, args, 1); return Math.abs(args.get(0));
                case "round":
                    require(funcName, args, 1); return Math.rint(args.get(0));
                case "floor":
                    require(funcName, args, 1); return Math.floor(args.get(0));
                case "ceil":
                    require(funcName, args, 1); return Math.ceil(args.get(0));
                case "sin":
                    require(funcName, args, 1); return Math.sin(args.get(0));
                case "cos":
                    require(funcName, args, 1); return Math.cos(args.get(0));
                case "tan":
                    require(funcName, args, 1); return Math.tan(args.get(0));
                case "log":
                    require(funcName, args, 1);
                    if (args.get(0) <= 0) throw new ArithmeticException("log 非正数");
                    return Math.log(args.get(0));
                case "log10":
                    require(funcName, args, 1);
                    if (args.get(0) <= 0) throw new ArithmeticException("log10 非正数");
                    return Math.log10(args.get(0));
                case "exp":
                    require(funcName, args, 1); return Math.exp(args.get(0));
                case "pow":
                    require(funcName, args, 2); return Math.pow(args.get(0), args.get(1));
                case "min":
                    requireAtLeast(funcName, args, 1);
                    return args.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
                case "max":
                    requireAtLeast(funcName, args, 1);
                    return args.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
                default:
                    throw new IllegalArgumentException("不支持的函数 " + funcName);
            }
        }

        private void require(String funcName, List<Double> args, int count) {
            if (args.size() != count) {
                throw new IllegalArgumentException(funcName + " 需要 " + count + " 个参数，实际 " + args.size());
            }
        }

        private void requireAtLeast(String funcName, List<Double> args, int count) {
            if (args.size() < count) {
                throw new IllegalArgumentException(funcName + " 至少需要 " + count + " 个参数");
            }
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        private boolean eat(char expected) {
            skipSpaces();
            if (pos < text.length() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }
    }
}