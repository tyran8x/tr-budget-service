package vn.tr.budget.data.validator;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.tr.common.core.exception.ServiceException;

/**
 * Kiểm tra an toàn cú pháp biểu thức công thức định mức trên Cây Dự toán Thu - Chi, bao gồm chống
 * Excel Formula Injection, xác thực mã tham chiếu tồn tại và phát hiện vòng lặp gián tiếp (cycle
 * detection A → B → A) trên đồ thị công thức.
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

  private static final int DFS_UNVISITED = 0;
  private static final int DFS_VISITING = 1;
  private static final int DFS_VISITED = 2;

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
    return inspectFormula(formula, selfCode, null);
  }

  /**
   * Phân tích công thức và tùy chọn kiểm tra mã tham chiếu {@code [CODE]} có tồn tại trong tập
   * {@code validCodes} (không phân biệt hoa/thường).
   */
  public static List<String> inspectFormula(
      String formula, String selfCode, Set<String> validCodes) {
    List<String> errors = new ArrayList<>();
    if (StrUtil.isBlank(formula)) {
      return errors;
    }

    String trimmed = formula.trim();
    if (trimmed.startsWith("+") || trimmed.startsWith("-")) {
      errors.add(
          "Công thức không được bắt đầu bằng ký tự '+' hoặc '-' (chống tiêm công thức bảng tính): "
              + trimmed);
      return errors;
    }
    if (trimmed.contains("..") || FORBIDDEN_CHARS.matcher(trimmed).find()) {
      errors.add("Công thức chứa ký tự hoặc cú pháp không được phép: " + trimmed);
      return errors;
    }

    Set<String> upperValidCodes = null;
    if (validCodes != null && !validCodes.isEmpty()) {
      upperValidCodes = new LinkedHashSet<>();
      for (String c : validCodes) {
        if (StrUtil.isNotBlank(c)) {
          upperValidCodes.add(c.trim().toUpperCase(Locale.ROOT));
        }
      }
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
      } else if (upperValidCodes != null
          && !upperValidCodes.contains(refCode.toUpperCase(Locale.ROOT))) {
        errors.add(
            "Công thức tham chiếu mã chỉ tiêu không tồn tại trên Cây Dự toán: [" + refCode + "]");
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

  /** Trích xuất danh sách mã chỉ tiêu tham chiếu {@code [CODE]} (đã chuẩn hóa IN HOA). */
  public static Set<String> extractReferencedCodes(String formula) {
    Set<String> refs = new LinkedHashSet<>();
    if (StrUtil.isBlank(formula)) {
      return refs;
    }
    Matcher refMatcher = REF_PATTERN.matcher(formula.trim());
    while (refMatcher.find()) {
      String refCode = refMatcher.group(1) != null ? refMatcher.group(1).trim() : "";
      if (VALID_REF_CODE.matcher(refCode).matches()) {
        refs.add(refCode.toUpperCase(Locale.ROOT));
      }
    }
    return refs;
  }

  /**
   * Kiểm tra đồ thị công thức trên toàn Cây Dự toán nhằm phát hiện vòng lặp tham chiếu gián tiếp
   * (ví dụ {@code A -> [B] -> [C] -> [A]}).
   */
  public static List<String> validateFormulaGraph(Map<String, String> formulaByCode) {
    List<String> cycleErrors = new ArrayList<>();
    if (formulaByCode == null || formulaByCode.isEmpty()) {
      return cycleErrors;
    }
    Map<String, Set<String>> adjacency = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : formulaByCode.entrySet()) {
      if (StrUtil.isBlank(entry.getKey())) {
        continue;
      }
      String node = entry.getKey().trim().toUpperCase(Locale.ROOT);
      adjacency.put(node, extractReferencedCodes(entry.getValue()));
    }
    Map<String, Integer> color = new HashMap<>();
    List<String> stack = new ArrayList<>();
    for (String node : adjacency.keySet()) {
      if (color.getOrDefault(node, DFS_UNVISITED) == DFS_UNVISITED) {
        detectCyclesDfs(node, adjacency, color, stack, cycleErrors);
      }
    }
    return cycleErrors;
  }

  /** Kiểm tra đồ thị công thức và ném {@link ServiceException} nếu tồn tại vòng lặp tham chiếu. */
  public static void validateFormulaGraphOrThrow(Map<String, String> formulaByCode) {
    List<String> issues = validateFormulaGraph(formulaByCode);
    if (!issues.isEmpty()) {
      throw new ServiceException(issues.getFirst());
    }
  }

  private static void detectCyclesDfs(
      String current,
      Map<String, Set<String>> adjacency,
      Map<String, Integer> color,
      List<String> stack,
      List<String> cycleErrors) {
    color.put(current, DFS_VISITING);
    stack.add(current);
    Set<String> neighbors = adjacency.getOrDefault(current, Set.of());
    for (String next : neighbors) {
      if (!adjacency.containsKey(next)) {
        continue;
      }
      int nextColor = color.getOrDefault(next, DFS_UNVISITED);
      if (nextColor == DFS_VISITING) {
        int startIdx = stack.indexOf(next);
        List<String> cyclePath = new ArrayList<>(stack.subList(startIdx, stack.size()));
        cyclePath.add(next);
        cycleErrors.add(
            "Phát hiện vòng lặp tham chiếu công thức giữa các chỉ tiêu: "
                + String.join(" -> ", cyclePath));
      } else if (nextColor == DFS_UNVISITED) {
        detectCyclesDfs(next, adjacency, color, stack, cycleErrors);
      }
    }
    stack.remove(stack.size() - 1);
    color.put(current, DFS_VISITED);
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
