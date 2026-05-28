package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testReportConverterWorkflow() {
    val reportText = """
                     ميزان مراجعة بالشركة العربية
      رقم الحساب             اسم الحساب              الرصيد الافتتاحي       الحركات المدنية     الحركات الدائنة       الرصيد الحالي
      101                  حساب الصندوق                15000               2000                5000                12000
      =========================================================================================================
      102                  حساب البنك                   50000               10000               15000               45000
    """.trimIndent()

    val parsed = ReportConverter.parseWithSmartValleyDetector(reportText)
    assertNotNull(parsed)
    assertTrue(parsed.isNotEmpty())

    val excelBytes = ReportConverter.generateExcel(parsed)
    assertNotNull(excelBytes)
    assertTrue(excelBytes.isNotEmpty())
  }
}

