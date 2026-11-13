const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validNotePayload(ownerId, overrides = {}) {
  const past = new Date(Date.now() - 2000);
  return {
    ownerId,
    ownerName: "Alice",
    workspaceId: "",
    memberUids: [ownerId],
    title: "Follow up on Q4 vendor quote",
    transcript: "Need to verify Q4 pricing with vendor before Friday.",
    aiSummary: "Confirm vendor Q4 pricing before Friday.",
    category: "Official",
    tags: ["vendor", "q4"],
    keywords: ["pricing", "vendor", "quote"],
    actionItem: "Email vendor procurement team",
    priority: "high",
    status: "open",
    resolutionNote: "",
    resolvedByUid: "",
    resolvedByName: "",
    assigneeName: "Alice",
    localAudioFileName: "note_1.m4a",
    audioDurationSec: 18,
    dueAtMs: Date.now() + 86400000,
    reminderMinutesBefore: 60,
    createdAt: past,
    updatedAt: past,
    ...overrides,
  };
}

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
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read notes or workspaces", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("notes").get());
  await assertFails(unauthDb.collection("workspaces").get());
  await assertFails(unauthDb.collection("workspace_invites").doc("ABC123").get());
});

test("Authenticated user can create, query, update, and resolve own note", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const noteRef = aliceDb.collection("notes").doc("note_alice_1");

  await assertSucceeds(noteRef.set(validNotePayload(ALICE_UID)));

  await assertSucceeds(
    aliceDb.collection("notes").where("memberUids", "array-contains", ALICE_UID).get()
  );

  // Update action item
  await assertSucceeds(
    noteRef.update({
      actionItem: "Call procurement lead",
      status: "in_progress",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Resolve and close
  const closedTime = new Date(Date.now() - 500);
  await assertSucceeds(
    noteRef.update({
      status: "closed",
      resolutionNote: "Vendor confirmed 8% discount via email.",
      resolvedByUid: ALICE_UID,
      resolvedByName: "Alice",
      closedAt: closedTime,
      updatedAt: closedTime,
    })
  );

  // Terminal state lock: cannot edit once closed
  await assertFails(
    noteRef.update({
      title: "Tamper after close",
      updatedAt: new Date(Date.now() - 100),
    })
  );
});

test("Authenticated user cannot list notes without memberUids filter or read another user's note", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("notes").doc("bob_note_1").set(validNotePayload(BOB_UID));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("notes").get());
  await assertFails(aliceDb.collection("notes").doc("bob_note_1").get());
  await assertFails(aliceDb.collection("notes").doc("bob_note_1").delete());
});

test("Shadow update with ghost field is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const noteRef = aliceDb.collection("notes").doc("note_ghost");
  await assertFails(
    noteRef.set(validNotePayload(ALICE_UID, { isVerified: true }))
  );
});

test("Workspace creation, invite lookup, and member join workflow", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const past = new Date(Date.now() - 1500);

  // Batch create workspace + invite
  const batch = aliceDb.batch();
  const wsRef = aliceDb.collection("workspaces").doc("ws_ops_1");
  const invRef = aliceDb.collection("workspace_invites").doc("OPS999");

  batch.set(wsRef, {
    name: "Operations Sync",
    ownerId: ALICE_UID,
    ownerName: "Alice",
    joinCode: "OPS999",
    memberUids: [ALICE_UID],
    memberNames: ["Alice"],
    createdAt: past,
    updatedAt: past,
  });
  batch.set(invRef, {
    workspaceId: "ws_ops_1",
    workspaceName: "Operations Sync",
    ownerId: ALICE_UID,
    createdAt: past,
  });
  await assertSucceeds(batch.commit());

  // Listing all invites must fail
  await assertFails(bobDb.collection("workspace_invites").get());

  // Direct get by code succeeds
  await assertSucceeds(bobDb.collection("workspace_invites").doc("OPS999").get());

  // Bob joins workspace by appending himself
  await assertSucceeds(
    bobDb.collection("workspaces").doc("ws_ops_1").update({
      memberUids: [ALICE_UID, BOB_UID],
      memberNames: ["Alice", "Bob"],
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Bob can now read workspace
  await assertSucceeds(bobDb.collection("workspaces").doc("ws_ops_1").get());
});
