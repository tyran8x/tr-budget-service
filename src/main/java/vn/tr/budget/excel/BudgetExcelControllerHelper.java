package vn.tr.budget.excel;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongPredicate;
import org.springframework.web.multipart.MultipartFile;
import vn.tr.common.core.exception.base.BaseException;
import vn.tr.common.core.utils.MessageUtils;
import vn.tr.common.excel.core.GlobalExcelMapper;
import vn.tr.common.excel.core.GlobalExcelService;
import vn.tr.common.excel.core.ImportBatchResult;
import vn.tr.common.excel.core.ImportRowResult;
import vn.tr.common.web.data.criteria.BaseSearchCriteria;
import vn.tr.common.web.data.dto.BaseSimpleData;

/**
 * Tiện ích dùng chung cho các Controller Ngân sách khi kiểm tra và nạp dữ liệu Excel, loại bỏ mã
 * lặp giữa 7 Controller và tránh inject singleton {@code HttpServletResponse}.
 *
 * @author tyran8x
 */
public final class BudgetExcelControllerHelper {

  private BudgetExcelControllerHelper() {}

  public static <E, I, D extends BaseSimpleData, C extends BaseSearchCriteria>
      Map<String, Object> validateImport(
      MultipartFile file,
      GlobalExcelService<E, I, D, C> excelService,
      GlobalExcelMapper<E, D, I> mapper) {
    try (InputStream is = file.getInputStream()) {
      ImportBatchResult<I> batchResult =
          excelService.validateExcel(is, file.getOriginalFilename(), true);

      List<D> dataList = new ArrayList<>();
      List<Map<String, Object>> errorsList = new ArrayList<>();

      for (ImportRowResult<I> rowResult : batchResult.getRowResults()) {
        dataList.add(mapper.toImportData(rowResult.getOriginalData()));
        if (rowResult.hasErrors()) {
          for (String err : rowResult.getValidationErrors()) {
            Map<String, Object> errMap = new HashMap<>();
            errMap.put("row", rowResult.getRowNumber());
            errMap.put("field", "");
            errMap.put("message", err);
            errorsList.add(errMap);
          }
        }
      }

      Map<String, Object> summary = new HashMap<>();
      summary.put("total", batchResult.getTotalRows());
      summary.put("valid", batchResult.getTotalRows() - batchResult.getErrorCount());
      summary.put("invalid", batchResult.getErrorCount());

      Map<String, Object> result = new HashMap<>();
      result.put("data", dataList);
      result.put("errors", errorsList);
      result.put("summary", summary);
      result.put("valid", batchResult.getErrorCount() == 0);
      return result;
    } catch (IOException e) {
      throw new BaseException(MessageUtils.message("error.excel.read", e.getMessage()));
    }
  }

  public static <D> Map<String, Object> importData(
      List<D> dataList,
      Function<D, Long> idExtractor,
      LongPredicate existsCheck,
      Consumer<D> createFn,
      java.util.function.BiConsumer<Long, D> updateFn) {
    if (dataList == null || dataList.isEmpty()) {
      throw new BaseException("Dữ liệu import trống");
    }

    int created = 0;
    int updated = 0;
    List<Map<String, Object>> errors = new ArrayList<>();

    for (int i = 0; i < dataList.size(); i++) {
      D data = dataList.get(i);
      try {
        Long id = idExtractor.apply(data);
        if (id != null && id > 0 && existsCheck.test(id)) {
          updateFn.accept(id, data);
          updated++;
        } else {
          createFn.accept(data);
          created++;
        }
      } catch (Exception e) {
        Map<String, Object> errMap = new HashMap<>();
        errMap.put("row", i + 1);
        errMap.put("message", e.getMessage());
        errors.add(errMap);
      }
    }

    Map<String, Object> result = new HashMap<>();
    result.put("created", created);
    result.put("updated", updated);
    result.put("errors", errors);
    return result;
  }
}
