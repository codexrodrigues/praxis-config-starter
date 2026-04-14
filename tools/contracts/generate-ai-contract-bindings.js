#!/usr/bin/env node
/*
 * Generates backend/frontend contract constants from the canonical OpenAPI file.
 * This script is intentionally dependency-free (Node stdlib only).
 */
const fs = require('fs');
const path = require('path');

const STARTER_ROOT = path.resolve(__dirname, '..', '..');
const MONOREPO_ROOT = path.resolve(STARTER_ROOT, '..');
const DEFAULT_UI_REPO_ROOT = path.join(MONOREPO_ROOT, 'praxis-ui-angular');
const OPENAPI_PATH = path.join(
  STARTER_ROOT,
  'docs',
  'ai',
  'contracts',
  'praxis-ai-api-contract-v1.1.openapi.yaml',
);
const JAVA_OUTPUT_PATH = path.join(
  STARTER_ROOT,
  'src',
  'main',
  'java',
  'org',
  'praxisplatform',
  'config',
  'contract',
  'AiContractSpec.java',
);
const UI_OUTPUT_PATH = process.env.AI_CONTRACT_UI_OUTPUT || path.join(
  DEFAULT_UI_REPO_ROOT,
  'projects',
  'praxis-ai',
  'src',
  'lib',
  'core',
  'contracts',
  'ai-contract.generated.ts',
);

function extractRequired(text, regex, label) {
  const match = text.match(regex);
  if (!match || !match[1]) {
    throw new Error(`Could not extract ${label} from OpenAPI contract.`);
  }
  return match[1].trim().replace(/^['"]|['"]$/g, '');
}

function normalizeEnumItems(raw) {
  return raw
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
    .map((item) => item.replace(/^['"]|['"]$/g, ''));
}

function parseContract(openApiText) {
  const contractVersion = extractRequired(
    openApiText,
    /^  version:\s*([^\n#]+)$/m,
    'info.version',
  );
  const schemaHash = extractRequired(
    openApiText,
    /ContractSchemaHashHeader:[\s\S]*?default:\s*([a-f0-9]{32,128})/i,
    'ContractSchemaHashHeader.default',
  );
  const streamEventSchemaVersion = extractRequired(
    openApiText,
    /AiPatchStreamStartResponse:[\s\S]*?eventSchemaVersion:[\s\S]*?example:\s*([^\n#]+)/m,
    'AiPatchStreamStartResponse.eventSchemaVersion.example',
  );
  const eventTypesRaw = extractRequired(
    openApiText,
    /AiTurnEventEnvelope:[\s\S]*?type:[\s\S]*?enum:\s*\[([^\]]+)\]/m,
    'AiTurnEventEnvelope.type.enum',
  );
  const streamEventTypes = normalizeEnumItems(eventTypesRaw);
  if (streamEventTypes.length === 0) {
    throw new Error('Extracted stream event types is empty.');
  }
  return {
    contractVersion,
    schemaHash,
    streamEventSchemaVersion,
    streamEventTypes,
  };
}

function escapeJava(value) {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

function escapeTs(value) {
  return value.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

function renderJava(contract) {
  const eventList = contract.streamEventTypes
    .map((value) => `"${escapeJava(value)}"`)
    .join(', ');
  return `package org.praxisplatform.config.contract;

import java.util.List;

/**
 * Generated from docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml.
 * Do not edit manually. Run tools/contracts/generate-ai-contract-bindings.js.
 */
public final class AiContractSpec {

    public static final String CONTRACT_VERSION = "${escapeJava(contract.contractVersion)}";
    public static final String CONTRACT_SCHEMA_HASH = "${escapeJava(contract.schemaHash)}";
    public static final String STREAM_EVENT_SCHEMA_VERSION = "${escapeJava(contract.streamEventSchemaVersion)}";
    public static final List<String> STREAM_EVENT_TYPES = List.of(${eventList});

    private AiContractSpec() {
    }
}
`;
}

function renderTs(contract) {
  const eventList = contract.streamEventTypes
    .map((value) => `'${escapeTs(value)}'`)
    .join(', ');
  return `/**
 * Generated from praxis-config-starter/docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml.
 * Do not edit manually. Run praxis-config-starter/tools/contracts/generate-ai-contract-bindings.js.
 */
export const AI_CONTRACT_VERSION = '${escapeTs(contract.contractVersion)}' as const;
export const AI_CONTRACT_SCHEMA_HASH = '${escapeTs(contract.schemaHash)}' as const;
export const AI_STREAM_EVENT_SCHEMA_VERSION = '${escapeTs(contract.streamEventSchemaVersion)}' as const;
export const AI_STREAM_EVENT_TYPES = [${eventList}] as const;

export type AiJsonPrimitive = string | number | boolean | null;
export type AiJsonArray = AiJsonValue[];
export interface AiJsonObject {
  [key: string]: AiJsonValue;
}
export type AiJsonValue = AiJsonPrimitive | AiJsonObject | AiJsonArray;

export interface AiSchemaContextContract {
  path?: string | null;
  operation?: string | null;
  schemaType?: string | null;
}

export interface AiChatMessageContract {
  role: 'user' | 'assistant' | 'system';
  content: string;
}

export interface AiUiContextRefContract {
  componentType?: string | null;
  componentId?: string | null;
  routeKey?: string | null;
  schemaHash?: string | null;
  variantId?: string | null;
}

export interface AiCurrentStateDigestContract {
  columns?: string[] | null;
  sort?: string | null;
  rowCount?: number | null;
}

export interface AiOrchestratorRequestContract {
  componentId: string;
  componentType: string;
  userPrompt?: string;
  sessionId?: string;
  mode?: 'new' | 'continue';
  clientTurnId?: string;
  messages?: AiChatMessageContract[];
  summary?: string;
  uiContextRef?: AiUiContextRefContract;
  currentStateDigest?: AiCurrentStateDigestContract;
  currentState: AiJsonObject;
  dataProfile?: AiJsonObject | null;
  schemaFields?: AiJsonObject[] | null;
  runtimeState?: AiJsonObject | null;
  suggestedPatch?: AiJsonObject | null;
  contextHints?: AiJsonObject | null;
  aiMode?: string;
  requireSchema?: boolean;
  resourcePath?: string;
  contractVersion?: string;
  schemaHash?: string;
  schemaContext?: AiSchemaContextContract | null;
  variantId?: string;
  apiMethod?: string;
  apiTags?: string;
  apiSearchLimit?: number;
}

export type AiOrchestratorResponseType = 'patch' | 'clarification' | 'error' | 'info';

export interface AiPatchDiffContract {
  path: string;
  before?: AiJsonValue;
  after?: AiJsonValue;
}

export interface AiOptionContract {
  value?: string | null;
  label?: string | null;
  example?: string | null;
  contextHints?: AiJsonObject | null;
}

export interface AiClarificationUiContract {
  responseType?: 'text' | 'choice' | 'confirm' | 'mixed' | 'context';
  selectionMode?: 'single' | 'multiple';
  presentation?: 'buttons' | 'list' | 'chips';
  allowCustom?: boolean | null;
}

export interface AiMemoryInfoContract {
  summaryUpdated?: boolean | null;
  windowSize?: number | null;
  cached?: boolean | null;
}

export interface AiOrchestratorResponseContract {
  sessionId?: string | null;
  code?: string | null;
  type?: AiOrchestratorResponseType;
  contractVersion?: string | null;
  schemaHash?: string | null;
  patch?: AiJsonObject | null;
  componentEditPlan?: AiJsonObject | null;
  diff?: AiPatchDiffContract[] | null;
  explanation?: string | null;
  warnings?: string[] | null;
  message?: string | null;
  options?: string[] | null;
  optionPayloads?: AiOptionContract[] | null;
  contextRequest?: number[] | null;
  clarification?: AiClarificationUiContract;
  componentId?: string | null;
  componentType?: string | null;
  path?: string | null;
  providedValue?: AiJsonValue;
  allowedValues?: string[] | null;
  memory?: AiMemoryInfoContract;
}

export interface AiPatchStreamStartResponseContract {
  streamId: string;
  threadId: string;
  turnId: string;
  eventSchemaVersion: string;
  streamAuthMode?: 'cookie' | 'signed_url_token' | null;
  streamAccessToken?: string | null;
  expiresAt: string;
  fallbackPatchUrl: string;
}

export interface AiPatchStreamCancelResponseContract {
  streamId?: string | null;
  threadId?: string | null;
  turnId?: string | null;
  terminalState: 'cancelled' | 'completed' | 'not_found';
  message?: string | null;
}

export interface AiPatchStreamEnvelopeContract<TPayload extends AiJsonObject = AiJsonObject> {
  eventId?: string | null;
  streamId: string;
  threadId: string;
  turnId: string;
  seq: number;
  eventSchemaVersion: string;
  timestamp: string;
  type: AiPatchStreamEventType;
  payload: TPayload;
}

export interface ProblemResponseContract {
  timestamp?: string | null;
  status?: number | null;
  error?: string | null;
  message?: string | null;
  path?: string | null;
  detail?: string | null;
  [key: string]: AiJsonValue | undefined;
}

export type AiPatchStreamEventType = (typeof AI_STREAM_EVENT_TYPES)[number];
`;
}

function writeFile(targetPath, content) {
  fs.mkdirSync(path.dirname(targetPath), { recursive: true });
  fs.writeFileSync(targetPath, content, { encoding: 'utf8' });
}

function main() {
  if (!fs.existsSync(OPENAPI_PATH)) {
    throw new Error(`OpenAPI contract not found: ${OPENAPI_PATH}`);
  }
  const contractText = fs.readFileSync(OPENAPI_PATH, { encoding: 'utf8' });
  const parsed = parseContract(contractText);
  writeFile(JAVA_OUTPUT_PATH, renderJava(parsed));
  const explicitUiOutput = Boolean(process.env.AI_CONTRACT_UI_OUTPUT);
  if (explicitUiOutput || fs.existsSync(DEFAULT_UI_REPO_ROOT)) {
    writeFile(UI_OUTPUT_PATH, renderTs(parsed));
  } else {
    console.warn(`[ai-contract] UI project not found at ${DEFAULT_UI_REPO_ROOT}. Skipping TS output.`);
  }
  console.log(`[ai-contract] Generated Java contract binding: ${path.relative(STARTER_ROOT, JAVA_OUTPUT_PATH)}`);
  if (explicitUiOutput || fs.existsSync(DEFAULT_UI_REPO_ROOT)) {
    console.log(`[ai-contract] Generated TS contract binding: ${UI_OUTPUT_PATH}`);
  }
}

main();
