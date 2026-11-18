package com.example.data.local

import com.example.model.*

object SeedData {
    val defaultDemoUsers: List<AppUser> = listOf(
        AppUser(
            id = "usr-auditor-1",
            jobId = "AUD-101",
            phone = "0501112233",
            name = "أحمد حمادة",
            role = UserRole.AUDITOR,
            pinCode = "1234",
            department = "الرقابة والتدقيق",
            title = "مدير ومراقب عمليات المستودع",
            signatureText = "مراقب عمليات المستودع - أحمد حمادة",
            avatarColor = "emerald",
            createdAt = "2026-01-01T00:00:00Z"
        ),
        AppUser(
            id = "usr-sup-1",
            jobId = "SUP-201",
            phone = "0502223344",
            name = "محمد العتيبي",
            role = UserRole.SUPERVISOR,
            pinCode = "1234",
            department = "إشراف الصادر",
            title = "مشرف المستودع العام",
            signatureText = "مشرف مستودع - محمد العتيبي",
            avatarColor = "indigo",
            createdAt = "2026-01-01T00:00:00Z"
        ),
        AppUser(
            id = "usr-wk-1",
            jobId = "WK-301",
            phone = "0503334455",
            name = "خالد السعيد",
            role = UserRole.WAREHOUSE_KEEPER,
            pinCode = "1234",
            department = "الاستلام والتجهيز",
            title = "أمين مستودع رئيسي",
            signatureText = "أمين مستودع - خالد السعيد",
            avatarColor = "cyan",
            createdAt = "2026-01-01T00:00:00Z"
        ),
        AppUser(
            id = "usr-guest-1",
            jobId = "GUEST-01",
            phone = "0500000000",
            name = "زائر تجريبي",
            role = UserRole.GUEST,
            pinCode = "0000",
            department = "استعراض",
            title = "مستخدم ضيف (قراءة فقط)",
            signatureText = "زائر",
            avatarColor = "amber",
            createdAt = "2026-01-01T00:00:00Z"
        )
    )

    val defaultWorkers: List<WarehouseWorker> = listOf(
        WarehouseWorker("w-1", "أحمد الشهري", "W-01", WorkerExperienceLevel.EXPERT, true, "0551110001", "الزجاج والحساس والسيروم", "2026-01-01T00:00:00Z"),
        WarehouseWorker("w-2", "سعيد الغامدي", "W-02", WorkerExperienceLevel.EXPERT, true, "0551110002", "الدستات والمقاسات الدقيقة", "2026-01-01T00:00:00Z"),
        WarehouseWorker("w-3", "فهد القحطاني", "W-03", WorkerExperienceLevel.INTERMEDIATE, true, "0551110003", "مستلزمات الحفلات والتوزيع", "2026-01-01T00:00:00Z"),
        WarehouseWorker("w-4", "خالد الحربي", "W-04", WorkerExperienceLevel.INTERMEDIATE, true, "0551110004", "الأدوات المنزلية والإكسسوار", "2026-01-01T00:00:00Z"),
        WarehouseWorker("w-5", "ماجد الدوسري", "W-05", WorkerExperienceLevel.NOVICE, true, "0551110005", "الكراتين المغلقة والأكواب", "2026-01-01T00:00:00Z"),
        WarehouseWorker("w-6", "عمر الزهراني", "W-06", WorkerExperienceLevel.NOVICE, true, "0551110006", "البلاستيك والمناديل الجملة", "2026-01-01T00:00:00Z")
    )

    val defaultPackagingRules: List<PackagingGroupRule> = listOf(
        PackagingGroupRule("rule-1141", "أدوات مطبخ ستيل (1141)", "114100000", "114199999", "مطبخ", 12.0, 1.0, "HDZ", "HDZ", "PCS", "12 HDZ", "72 PCS", "1BOX=6PCS", 12, "HDZ=.5DZ", "1CARTON=12BOX(HDZ)=6DZ", GroupDifficultyLevel.HIGH_EXPERT, "خبير - وزن وعدّ حساس", true, "وحدة النظام نصف دستة (12 بوكس/كرتون)", "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-1142", "أطقم ملاعق وشوك (1142)", "114200000", "114299999", "مطبخ", 6.0, 0.5, "DZ", "DZ", "PCS", "6 DZ", "72 PCS", "1BOX=6PCS", 12, "HDZ=.5DZ", "1CARTON=12BOX(HDZ)=6DZ", GroupDifficultyLevel.HIGH_EXPERT, "خبير - تفكيك بوكسات", true, "وحدة النظام دستة (6 دستة = 12 بوكس)", "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-2110", "مستلزمات زجاجية فاخرة (2110)", "211000000", "211099999", "زجاج", 12.0, 6.0, "EA", "EA", "PCS", "12 EA", "12 PCS", "1BOX=6PCS", 2, "EA=1/12DZ", "1CARTON=2BOX=12EA", GroupDifficultyLevel.HIGH_EXPERT, "خبير - قابل للكسر", true, "حساس للكسر 1 كرتون = 2 بوكس = 12 حبة", "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-2120", "أواني تقديم زجاج (2120)", "212000000", "212099999", "زجاج", 1.0, 0.5, "DZ", "DZ", "PCS", "1 DZ", "12 PCS", "1BOX=6PCS", 2, "EA=1/12DZ", "1CARTON=2BOX=12EA", GroupDifficultyLevel.HIGH_EXPERT, "خبير - معاملة الدستة", true, "وحدة الفاتورة دستة 1 كرتون = 1 دستة", "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-3100", "حافظات طعام بلاستيك (3100)", "310000000", "310099999", "بلاستيك", 24.0, 6.0, "EA", "EA", "PCS", "24 EA", "24 PCS", "1BOX=6PCS", 4, "EA=EA", "1CARTON=4BOX=24EA", GroupDifficultyLevel.MEDIUM_INTERMEDIATE, "متوسط", true, "1 كرتون = 4 بوكس = 24 حبة", "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-3200", "ترامس ومشروبات (3200)", "320000000", "320099999", "منزلي", 2.0, 0.5, "DZ", "DZ", "PCS", "2 DZ", "24 PCS", "1BOX=6PCS", 4, "EA=1/12DZ", "1CARTON=4BOX=24EA", GroupDifficultyLevel.MEDIUM_INTERMEDIATE, "متوسط", true, "1 كرتون = 2 دستة", "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-4100", "أدوات نظافة وإسفنج (4100)", "410000000", "410099999", "نظافة", 48.0, 6.0, "EA", "EA", "PCS", "48 EA", "48 PCS", "1BOX=6PCS", 8, "EA=EA", "1CARTON=8BOX=48EA", GroupDifficultyLevel.MEDIUM_INTERMEDIATE, "متوسط", true, "1 كرتون = 8 بوكس = 48 حبة", "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-4200", "مفارش وسفر طعام (4200)", "420000000", "420099999", "بلاستيك", 4.0, 0.5, "DZ", "DZ", "PCS", "4 DZ", "48 PCS", "1BOX=6PCS", 8, "DZ=12EA", "1CARTON=8BOX=48EA", GroupDifficultyLevel.MEDIUM_INTERMEDIATE, "متوسط", true, "1 كرتون = 4 دستة", "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-5111", "أكواب ورقية وبلاستيك (5111)", "511100000", "511199999", "تغليف", 24.0, 6.0, "PCS", "PCS", "PCS", "24 PCS", "24 PCS", "1BOX=6PCS", 4, "PCS=PCS", "1CARTON=4BOX=24PCS", GroupDifficultyLevel.LOW_NOVICE, "مبتدئ - كراتين سريعة", true, "مجموعة عالية الدوران", "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-5120", "مناديل ورقية جملة (5120)", "512000000", "512099999", "ورقيات", 20.0, 5.0, "PCS", "PCS", "PCS", "20 PCS", "20 PCS", "1PACK=5PCS", 4, "PCS=PCS", "1CARTON=4PACK=20PCS", GroupDifficultyLevel.LOW_NOVICE, "مبتدئ", true, "سحب كرتوني سريع", "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-6100", "أكياس قمامة وتخزين (6100)", "610000000", "610099999", "تغليف", 36.0, 6.0, "PCS", "PCS", "PCS", "36 PCS", "36 PCS", "1PACK=6PCS", 6, "PCS=PCS", "1CARTON=6PACK=36PCS", GroupDifficultyLevel.LOW_NOVICE, "مبتدئ", true, null, "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-7100", "شموع وهدايا فاخرة (7100)", "710000000", "710099999", "هدايا", 24.0, 4.0, "PCS", "PCS", "PCS", "24 PCS", "24 PCS", "1BOX=4PCS", 6, "PCS=PCS", "1CARTON=6BOX=24PCS", GroupDifficultyLevel.MEDIUM_INTERMEDIATE, "متوسط", true, null, "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-8100", "سيروم ومستحضرات تجميل (8100)", "810000000", "810099999", "عناية", 36.0, 6.0, "PCS", "PCS", "PCS", "36 PCS", "36 PCS", "1BOX=6PCS", 6, "PCS=PCS", "1CARTON=6BOX=36PCS", GroupDifficultyLevel.HIGH_EXPERT, "خبير - عناية وتجميل", true, "حساس للحرارة", "2026-01-01T00:00:00Z"),
        PackagingGroupRule("rule-9100", "مستلزمات حفلات (9100)", "910000000", "910099999", "حفلات", 48.0, 12.0, "PCS", "PCS", "PCS", "48 PCS", "48 PCS", "1BOX=12PCS", 4, "PCS=PCS", "1CARTON=4BOX=48PCS", GroupDifficultyLevel.LOW_NOVICE, "مبتدئ", true, null, "2026-01-01T00:00:00Z")
    )

    val defaultDemoMasterItems: List<MasterInvoiceItem> = listOf(
        MasterInvoiceItem(1L, "ORD-200910", "20450101", "114110055", "طقم سكاكين ستيل فاخر 6 قطع", "HDZ", 4, "2026-01-01T00:00:00Z", 0),
        MasterInvoiceItem(2L, "ORD-200910", "20450101", "511107200", "أكواب ورقية كرافت دبل 12 أونص", "PCS", 10, "2026-01-01T00:00:00Z", 1),
        MasterInvoiceItem(3L, "ORD-200910", "20450101", "310044112", "حافظة طعام زجاجية محكمة الإغلاق", "EA", 6, "2026-01-01T00:00:00Z", 2),
        MasterInvoiceItem(4L, "ORD-200911", "20450102", "211088401", "طقم كاسات زجاجي ضيافة 12 قطعة", "EA", 12, "2026-01-01T00:00:00Z", 3),
        MasterInvoiceItem(5L, "ORD-200911", "20450102", "512001122", "مناديل مائدة أبيض جملة", "PCS", 20, "2026-01-01T00:00:00Z", 4),
        MasterInvoiceItem(6L, "ORD-200912", "20450103", "810033445", "سيروم فيتامين سي أصلي 50 مل", "PCS", 6, "2026-01-01T00:00:00Z", 5),
        MasterInvoiceItem(7L, "ORD-200912", "20450103", "114110055", "طقم سكاكين ستيل فاخر 6 قطع", "HDZ", 2, "2026-01-01T00:00:00Z", 6)
    )
}
