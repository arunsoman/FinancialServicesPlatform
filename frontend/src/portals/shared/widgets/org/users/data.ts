import type { DataSources } from '../../core/sources';

/** GET /api/tenants/{tenantId}/users → UserPage (§9) */
export interface UserView {
  id: string;
  tenantId: string;
  username: string;
  fullName: string;
  email: string;
  phone: string | null;
  status: 'ACTIVE' | 'DISABLED' | 'ARCHIVED' | 'PENDING';
  isApplicant: boolean;
  checker: boolean;
  roles: { id: string; key: string; name: string }[];
  version: number;
  createdAt: string;
  updatedAt: string;
  archivedAt: string | null;
}

export interface UserPage {
  items: UserView[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  sort: string;
  counts: {
    CURRENT: number;
    PENDING: number;
    ACTIVE: number;
    DISABLED: number;
    ARCHIVED: number;
  };
  org: {
    id: string;
    legalName: string;
    type: string;
    subType: string;
    depth: number;
  };
}

export const sources: DataSources = {
  'org.users.list': {
    '*': {
      method: 'GET',
      path: '/api/tenants/{tenantId}/users',
      query: ['page', 'size', 'status', 'q', 'sort'],
    },
  },
};

export const commands: DataSources = {};

const user = (
  n: number,
  name: string,
  username: string,
  status: UserView['status'] = 'ACTIVE',
  roles: { id: string; key: string; name: string }[] = [
    { id: 'r1', key: 'operator', name: 'Operator' },
  ],
): UserView => ({
  id: `00000000-0000-0000-0000-00000000u00${n}`,
  tenantId: '00000000-0000-0000-0000-0000000000a1',
  username,
  fullName: name,
  email: `${username}@example.com`,
  phone: null,
  status,
  isApplicant: false,
  checker: false,
  roles,
  version: 1,
  createdAt: '2026-01-15T10:00:00Z',
  updatedAt: '2026-11-01T12:00:00Z',
  archivedAt: null,
});

/** workbench scenarios */
export const fixtures = {
  'org.users.list': {
    default: {
      items: [
        user(1, 'Aisha Rahman', 'aisha.rahman', 'ACTIVE', [
          { id: 'r1', key: 'admin', name: 'Admin' },
          { id: 'r2', key: 'operator', name: 'Operator' },
        ]),
        user(2, 'Ben Carter', 'ben.carter', 'ACTIVE', [
          { id: 'r2', key: 'operator', name: 'Operator' },
        ]),
        user(3, 'Clara Ng', 'clara.ng', 'DISABLED', [
          { id: 'r3', key: 'viewer', name: 'Viewer' },
        ]),
        user(4, 'Diego Morales', 'diego.m', 'ACTIVE', [
          { id: 'r1', key: 'admin', name: 'Admin' },
        ]),
        user(5, 'Elena Petrova', 'elena.p', 'PENDING', []),
      ],
      page: 0,
      size: 50,
      totalElements: 5,
      totalPages: 1,
      hasNext: false,
      sort: 'fullName,asc',
      counts: {
        CURRENT: 5,
        PENDING: 1,
        ACTIVE: 3,
        DISABLED: 1,
        ARCHIVED: 0,
      },
      org: {
        id: '00000000-0000-0000-0000-0000000000a1',
        legalName: 'Example Organization',
        type: 'ORG',
        subType: 'DISTRIBUTOR',
        depth: 1,
      },
    },
    empty: {
      items: [],
      page: 0,
      size: 50,
      totalElements: 0,
      totalPages: 0,
      hasNext: false,
      sort: 'fullName,asc',
      counts: {
        CURRENT: 0,
        PENDING: 0,
        ACTIVE: 0,
        DISABLED: 0,
        ARCHIVED: 0,
      },
      org: {
        id: '00000000-0000-0000-0000-0000000000a1',
        legalName: 'Example Organization',
        type: 'ORG',
        subType: 'DISTRIBUTOR',
        depth: 1,
      },
    },
    error: null,
  },
};
