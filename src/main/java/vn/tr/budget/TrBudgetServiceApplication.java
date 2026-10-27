package vn.tr.budget;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ứng dụng khởi chạy Microservice Quản lý Kinh phí Đa cấp, Cây Dự toán Thu - Chi,
 * Phân quyền Tự chủ Tài chính Đơn vị, Phân bổ Dự toán, Sổ cái Biến động Nguồn
 * (Bổ sung, Chuyển nguồn liên năm, Điều chuyển nội bộ), Giải ngân Kho bạc và Báo cáo Quyết toán.
 *
 * @author tyran8x
 */
@SpringBootApplication(scanBasePackages = {"vn.tr"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {"vn.tr"})
@EnableScheduling
@EnableAsync
public class TrBudgetServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(TrBudgetServiceApplication.class, args);
  }
}
