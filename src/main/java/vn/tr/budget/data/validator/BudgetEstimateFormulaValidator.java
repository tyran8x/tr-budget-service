package vn.tr.budget.data.validator;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.tr.common.core.exception.ServiceException;

/**
 * Kiểm tra an toàn cú pháp biểu thức công thức định mức trên Cây Dự toán Thu - Chi.
 *
 * @author tyran8x
 */
public final class BudgetEstimateFormulaValidator {

  private static final Set<String> ALLOWED_FUNCTIONS =
      Set.of("SUM", "AVG", "MIN", "MAX", "ROUND", "ABS");

  private static final Set<String> ALLOWED_VARIABLES =
      Set.of(
          "CHILDREN",
          "HEADCOUNT",
          "BASE_SALARY",
          "REVENUE",
          "NORM",
          "MONTHS",
          "UNITS",
          "RATE");

  private static final Pattern FORBIDDEN_CHARS =
      Pattern.compile("[;$`{}\\\\@#?:=<>!&|'\"\\x00-\\x1F]");

  private static final Pattern REF_PATTERN = Pattern.compile("\\[([^\\]]*)\\]");
  private static final Pattern VALID_REF_CODE = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
  private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern DIV_ZERO_PATTERN =
      Pattern.compile("/\\s*0+(?:\\.0+)?(?![0-9.])");
  private static final Pattern ALLOWED_REMAINING_CHARS =
      Pattern.compile("^[A-Za-z0-9_.+,\\-*/%()\\s]*$");

  private BudgetEstimateFormulaValidator() {}

  /** Kiểm tra và ném {@link ServiceException} nếu công thức không hợp lệ. */
  public static void validateOrThrow(String formula, String selfCode) {
    List<String> issues = inspectFormula(formula, selfCode);
    if (!issues.isEmpty()) {
      throw new ServiceException(issues.getFirst());
    }
  }

  /** Phân tích công thức trả về danh sách cảnh báo/lỗi (rỗng nếu hợp lệ). */
  public static List<String> inspectFormula(String formula, String selfCode) {
    List<String> errors = new ArrayList<>();
    if (StrUtil.isBlank(formula)) {
      return errors;
    }

    String trimmed = formula.trim();
    if (trimmed.contains("..") || FORBIDDEN_CHARS.matcher(trimmed).find()) {
      errors.add("Công thức chứa ký tự hoặc cú pháp không được phép: " + trimmed);
      return errors;
    }

    String normalizedSelf = StrUtil.isNotBlank(selfCode) ? selfCode.trim() : null;
    Matcher refMatcher = REF_PATTERN.matcher(trimmed);
    StringBuilder withoutRefs = new StringBuilder();
    while (refMatcher.find()) {
      String refCode = refMatcher.group(1) != null ? refMatcher.group(1).trim() : "";
      if (!VALID_REF_CODE.matcher(refCode).matches()) {
        errors.add("Tham chiếu chỉ tiêu trong ngoặc vuông không hợp lệ: [" + refCode + "]");
      } else if (normalizedSelf != null && normalizedSelf.equalsIgnoreCase(refCode)) {
        errors.add("Công thức không được tự tham chiếu chính mã chỉ tiêu [" + refCode + "]");
      }
      refMatcher.appendReplacement(withoutRefs, " NORM ");
    }
    refMatcher.appendTail(withoutRefs);

    String cleaned = withoutRefs.toString();
    if (cleaned.indexOf('[') >= 0 || cleaned.indexOf(']') >= 0) {
      errors.add("Dấu ngoặc vuông tham chiếu mã chỉ tiêu chưa được đóng/mở đúng cặp.");
      return errors;
    }

    if (!ALLOWED_REMAINING_CHARS.matcher(cleaned).matches()) {
      errors.add("Công thức chỉ hỗ trợ chữ, số, biến định mức và toán tử (+ - * / %).");
      return errors;
    }

    if (!hasBalancedParentheses(cleaned)) {
      errors.add("Dấu ngoặc tròn trong công thức không cân bằng hoặc bị rỗng ().");
    }

    if (DIV_ZERO_PATTERN.matcher(cleaned).find()) {
      errors.add("Công thức không được phép chia cho hằng số 0.");
    }

    Matcher idMatcher = IDENTIFIER_PATTERN.matcher(cleaned);
    while (idMatcher.find()) {
      String token = idMatcher.group();
      String upper = token.toUpperCase(Locale.ROOT);
      int afterEnd = skipWhitespace(cleaned, idMatcher.end());
      boolean followedByParen = afterEnd < cleaned.length() && cleaned.charAt(afterEnd) == '(';
      if (followedByParen) {
        if (!ALLOWED_FUNCTIONS.contains(upper)) {
          errors.add("Hàm không được hỗ trợ trong công thức định mức: " + token);
        }
      } else if (!ALLOWED_VARIABLES.contains(upper)) {
        errors.add("Biến hoặc định danh không hợp lệ trong công thức: " + token);
      }
    }

    return errors;
  }

  private static boolean hasBalancedParentheses(String expr) {
    int depth = 0;
    char prevNonSpace = 0;
    for (int i = 0; i < expr.length(); i++) {
      char c = expr.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        if (depth == 0 || prevNonSpace == '(') {
          return false;
        }
        depth--;
      }
      if (!Character.isWhitespace(c)) {
        prevNonSpace = c;
      }
    }
    return depth == 0;
  }

  private static int skipWhitespace(String s, int index) {
    int i = index;
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    return i;
  }
}
