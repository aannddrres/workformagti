/** Mirrors web.RequiredReadingRequest. */
export interface RequiredReadingRequest {
  item_type: string;
  item_id: number;
  target_department: string;
  due_date: string;
  priority: string;
}

/** Mirrors web.RequiredReadingResponse. */
export interface RequiredReadingItem {
  id: number;
  item_type: string;
  item_id: number;
  target_department: string;
  due_date: string;
  priority: string;
}

/** One department's share of a mandatory item (PO-40). An aggregate: no names. */
export interface MandatoryDepartmentRow {
  department: string;
  in_force: number;
  pending: number;
  read: number;
}

/** One named addressee; only within the caller's own leadership scope, or for a system administrator. */
export interface MandatoryAddressee {
  user_id: number;
  user_name: string;
  department: string;
  read: boolean;
  pending: boolean;
}

/** Mirrors web.MandatoryAddresseesResponse: who a mandatory item binds now, and at publication. */
export interface MandatoryAddressees {
  in_force_total: number;
  pending_total: number;
  departments: MandatoryDepartmentRow[];
  addressees: MandatoryAddressee[];
}

/** Mirrors web.MandatoryReachRefusalResponse: a 409 from creating or re-aiming an assignment. */
export interface MandatoryRefusal {
  detail: string;
  reason: string;
  blocked_total: number;
  blocked_departments: { department: string; count: number }[];
}
