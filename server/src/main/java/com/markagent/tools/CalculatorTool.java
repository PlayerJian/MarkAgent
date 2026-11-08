package com.markagent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 安全算术表达式求值工具。
 * <p>
 * 手写 tokenizer + 递归下降解析器（JDK 21 无内置脚本引擎），支持四则运算、幂、括号与
 * 常用函数（sqrt/abs/round/floor/ceil/min/max/log/log10/sin/cos/tan）。无任何副作用。
 */
@Component
public class CalculatorTool {

    @Tool(name = "calculator", description = "计算算术表达式。支持 + - * / % ^ 括号与 sqrt/abs/round/floor/ceil/min/max/log/log10/sin/cos/tan 函数，例如 \"2+3*4\" 或 \"sqrt(16)+2^3\"。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String calculate(@ToolParam(name = "expression", required = true,
            description = "算术表达式，如 \"2+3*4\"、\"sqrt(16)+2^3\"") String expression) {
        try {
            Parser parser = new Parser(expression);
            double result = parser.parse();
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                return "错误：结果不是有限数值（可能发生了除零或定义域错误）";
            }
            return format(result);
        } catch (ArithmeticException e) {
            return "错误：" + e.getMessage();
        }
    }

    private static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.10g", value);
    }

    /** 递归下降：expr → term (('+'|'-') term)*；term → factor (('*'|'/'|'%') factor)* */
    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src == null ? "" : src;
        }

        double parse() {
            double value = expr();
            skipSpace();
            if (pos < src.length()) {
                throw new ArithmeticException("无法解析的字符 '" + src.charAt(pos) + "'");
            }
            return value;
        }

        private double expr() {
            double value = term();
            while (true) {
                skipSpace();
                if (match('+')) {
                    value += term();
                } else if (match('-')) {
                    value -= term();
                } else {
                    return value;
                }
            }
        }

        private double term() {
            double value = factor();
            while (true) {
                skipSpace();
                if (match('*')) {
                    value *= factor();
                } else if (match('/')) {
                    double divisor = factor();
                    if (divisor == 0) {
                        throw new ArithmeticException("除数为零");
                    }
                    value /= divisor;
                } else if (match('%')) {
                    double divisor = factor();
                    if (divisor == 0) {
                        throw new ArithmeticException("除数为零");
                    }
                    value %= divisor;
                } else {
                    return value;
                }
            }
        }

        private double factor() {
            skipSpace();
            if (match('-')) {
                return -factor();
            }
            if (match('+')) {
                return factor();
            }
            double value = primary();
            skipSpace();
            if (match('^')) {
                return Math.pow(value, factor());
            }
            return value;
        }

        private double primary() {
            skipSpace();
            if (match('(')) {
                double value = expr();
                skipSpace();
                if (!match(')')) {
                    throw new ArithmeticException("缺少右括号");
                }
                return value;
            }
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new ArithmeticException("在位置 " + pos + " 处缺少数字或函数");
            }
            String token = src.substring(start, pos);
            if (Character.isLetter(token.charAt(0))) {
                return function(token);
            }
            return Double.parseDouble(token);
        }

        private double function(String name) {
            skipSpace();
            String func = name.toLowerCase(Locale.ROOT);
            if (func.equals("pi")) {
                return Math.PI;
            }
            if (func.equals("e")) {
                return Math.E;
            }
            if (!match('(')) {
                throw new ArithmeticException("函数 " + name + " 后缺少 '('");
            }
            double a = expr();
            skipSpace();
            if (func.equals("min") || func.equals("max")) {
                if (!match(',')) {
                    throw new ArithmeticException(func + " 需要两个参数");
                }
                double b = expr();
                skipSpace();
                if (!match(')')) {
                    throw new ArithmeticException("缺少右括号");
                }
                return func.equals("min") ? Math.min(a, b) : Math.max(a, b);
            }
            if (!match(')')) {
                throw new ArithmeticException("缺少右括号");
            }
            return switch (func) {
                case "sqrt" -> Math.sqrt(a);
                case "abs" -> Math.abs(a);
                case "round" -> Math.round(a);
                case "floor" -> Math.floor(a);
                case "ceil" -> Math.ceil(a);
                case "log" -> Math.log(a);
                case "log10" -> Math.log10(a);
                case "sin" -> Math.sin(a);
                case "cos" -> Math.cos(a);
                case "tan" -> Math.tan(a);
                default -> throw new ArithmeticException("未知函数 " + name);
            };
        }

        private void skipSpace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private boolean match(char c) {
            skipSpace();
            if (pos < src.length() && src.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }
    }
}
