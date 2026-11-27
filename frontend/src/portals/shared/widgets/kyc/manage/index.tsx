/**
 * kyc.manage — ONE widget for the whole KYC lifecycle, served by the KYC plugin
 * (backend/plugins/kyc, /v1/plugins/kyc via gateway; browser → BFF /api/plugins/kyc):
 * list with server-side paging, search and sorting · view · add · edit · submit ·
 * approve / reject / request info (four-eyes, person-anchored).
 *
 * Permission keys are core's (V2 seed) — the plugin contributes none:
 *   view            kyc.review
 *   add/edit/submit org.onboard_child (the plugin onboards on-behalf of the person)
 *   approve / info  kyc.approve
 *   reject          kyc.reject
 * The gateway asks core (the only PDP) per call; the widget only picks affordances.
 */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { KycManage } from './widget';

const kycManageWidget = defineWidget({
  id: 'kyc.manage',
  version: '1.1.0',
  label: 'KYC applications',
  description: 'List, search, view, add, edit and decide KYC applications — server-side paging, search and sorting.',
  category: 'Onboarding',
  contexts: ['ANY'],
  requires: ['kyc.review'],
  sources: ['kyc.cases.page', 'kyc.case.view', 'kyc.case.history', 'kyc.master.list', 'kyc.orgs.self'],
  commands: ['kyc.case.create', 'kyc.case.update', 'kyc.case.submit', 'kyc.case.decide'],
  actions: [
    { id: 'kyc.case.create', label: 'New application', cell: 'org.onboard_child', command: 'kyc.case.create', kind: 'primary' },
    { id: 'kyc.case.update', label: 'Edit application', cell: 'org.onboard_child', command: 'kyc.case.update' },
    { id: 'kyc.case.submit', label: 'Submit for review', cell: 'org.onboard_child', command: 'kyc.case.submit' },
    { id: 'kyc.approve', label: 'Approve', cell: 'kyc.approve', command: 'kyc.case.decide', kind: 'primary' },
    { id: 'kyc.request-info', label: 'Request info', cell: 'kyc.approve', command: 'kyc.case.decide', kind: 'ghost' },
    { id: 'kyc.reject', label: 'Reject', cell: 'kyc.reject', command: 'kyc.case.decide', kind: 'danger' },
  ],
  commandOperations: {
    'kyc.case.create': 'create',
    'kyc.case.update': 'modify',
    'kyc.case.submit': 'modify',
    'kyc.case.decide': 'approve',
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: KycManage,
});

export default kycManageWidget;
