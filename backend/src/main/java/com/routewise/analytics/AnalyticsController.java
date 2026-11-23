package com.routewise.analytics;

import java.time.LocalDate;

import com.routewise.analytics.AnalyticsService.Format;
import com.routewise.analytics.AnalyticsService.Period;
import com.routewise.analytics.AnalyticsService.Report;
import com.routewise.analytics.AnalyticsService.Savings;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analytics")
@Tag(name = "Analiz", description = "Tasarruf raporları (F9)")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/savings")
    @Operation(summary = "Dönemsel tasarruf",
            description = "Sevk edilmiş planlar, uygulanan koşu ile aynı kargoların naive baseline'ı. Dönem "
                    + "yüzdesi ağırlıklıdır (toplamlardan). `from`/`to` verilmezse bugüne kadar 12 hafta, 12 ay "
                    + "ya da 5 yıl. Plan yapılmamış dönem satır üretmez.")
    public Savings savings(@RequestParam(defaultValue = "MONTH") Period period,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.savings(period, from, to);
    }

    @GetMapping("/export")
    @Operation(summary = "Rapor indir",
            description = "XLSX (5 sayfa: özet, dönemsel tasarruf, günlük planlar, Seviye 1, Seviye 2 deneyi) ya "
                    + "da PDF. Aralık tasarrufla aynı; Seviye 1 aralıktaki karşılaştırmalardan, Seviye 2 son "
                    + "tamamlanan deneyden.")
    public ResponseEntity<byte[]> export(@RequestParam Format format,
                                         @RequestParam(defaultValue = "MONTH") Period period,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Report r = service.export(format, period, from, to);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(r.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(r.filename()).build()
                        .toString())
                .body(r.content());
    }
}
