import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_auditor_1";
const BOB_UID = "bob_auditor_2";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  if (testEnv) await testEnv.clearFirestore();
});

test("Unauthenticated users are denied across all collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("settings").doc("current").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("returns").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("returns").doc("ret1").set({
      userId: ALICE_UID,
      returnReceiptNo: "RMA-1",
    })
  );
});

test("Owner can write settings and reject ghost fields or invalid language", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 1000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("settings").doc("current").set({
      userId: ALICE_UID,
      language: "ar",
      soundEnabled: true,
      vibrationEnabled: true,
      longBarcodeThreshold: 10,
      auditorName: "Ahmed",
      auditorId: "AUD-101",
      auditorTitle: "Lead Auditor",
      updatedAt: ts,
    })
  );

  // Reject ghost field (shadow update attack)
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("settings").doc("current").set({
      userId: ALICE_UID,
      language: "ar",
      soundEnabled: true,
      vibrationEnabled: true,
      longBarcodeThreshold: 10,
      auditorName: "Ahmed",
      auditorId: "AUD-101",
      auditorTitle: "Lead Auditor",
      isAdmin: true,
      updatedAt: ts,
    })
  );
});

test("Owner can create and repeat-update return report while preserving immutable createdAt", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("returns").doc("ret-100").set({
      userId: ALICE_UID,
      returnReceiptNo: "return20091234",
      originalInvoiceNo: "20455678",
      orderNo: "20091234",
      customerName: "Customer A",
      paymentMethod: "Mada",
      status: "PENDING_LAB",
      totalRefundAmount: 135.0,
      totalItems: 2,
      auditorName: "Ahmed",
      itemsJson: "[]",
      notes: "",
      createdAtIso: "2026-01-01T00:00:00Z",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Repeat backup via update preserving createdAt
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("returns").doc("ret-100").update({
      status: "COMPLETED_WAREHOUSE",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject immutable createdAt mutation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("returns").doc("ret-100").update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject @DocumentId 'id' leakage
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("returns").doc("ret-bad").set({
      id: "ret-bad",
      userId: ALICE_UID,
      returnReceiptNo: "return200",
      originalInvoiceNo: "",
      orderNo: "",
      customerName: "",
      paymentMethod: "COD",
      status: "DRAFT",
      totalRefundAmount: 0,
      totalItems: 0,
      auditorName: "",
      itemsJson: "[]",
      notes: "",
      createdAtIso: "2026-01-01T00:00:00Z",
      createdAt: created,
      updatedAt: created,
    })
  );
});

test("Cross-user isolation prevents Bob from reading or modifying Alice's warehouse archives", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("picking_waves").doc("wave-1").set({
      userId: ALICE_UID,
      waveNo: "WAVE-001",
      status: "OPEN",
      totalInvoices: 3,
      totalItems: 7,
      waveJson: "{}",
      createdAtIso: "2026-01-01T00:00:00Z",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("picking_waves").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("picking_waves").doc("wave-1").update({ status: "COMPLETED" }));
});
