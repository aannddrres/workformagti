import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, firstValueFrom } from 'rxjs';
import { MandatoryAddressees, RequiredReadingItem, RequiredReadingRequest } from '../models/required-reading';

/** Wraps the admin required-readings endpoints -- see
 *  web.ComplianceController's create/by-item/update/delete 4 methods. */
@Injectable({ providedIn: 'root' })
export class RequiredReadingService {
  private readonly http = inject(HttpClient);

  byItem(itemType: string, itemId: number): Observable<RequiredReadingItem | null> {
    return this.http.get<RequiredReadingItem | null>(`/api/compliance/required-readings/by-item/${itemType}/${itemId}`);
  }

  /** PO-40: who this item's mandatory reading binds now and at publication, for the editor's warnings. */
  addressees(itemType: string, itemId: number): Observable<MandatoryAddressees> {
    return this.http.get<MandatoryAddressees>(`/api/compliance/required-readings/by-item/${itemType}/${itemId}/addressees`);
  }

  create(request: RequiredReadingRequest): Observable<RequiredReadingItem> {
    return this.http.post<RequiredReadingItem>('/api/compliance/required-readings', request);
  }

  update(id: number, request: RequiredReadingRequest): Observable<RequiredReadingItem> {
    return this.http.put<RequiredReadingItem>(`/api/compliance/required-readings/${id}`, request);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`/api/compliance/required-readings/${id}`);
  }

  /** Port of app-core.js's syncMandatoryFor: discovers the existing
   *  assignment for this item, then creates/updates it (mandatory checked
   *  with a due date) or deletes it (unchecked). Called after the
   *  Article/News/Video row itself is saved, on both create and edit. */
  async sync(
    itemType: string,
    itemId: number,
    targetDepartment: string,
    wantsMandatory: boolean,
    dueDateIso: string | null
  ): Promise<void> {
    const existing = await firstValueFrom(this.byItem(itemType, itemId)).catch(() => null);

    if (wantsMandatory && dueDateIso) {
      const request: RequiredReadingRequest = {
        item_type: itemType,
        item_id: itemId,
        target_department: targetDepartment,
        due_date: dueDateIso,
        priority: 'high'
      };
      if (existing?.id) {
        await firstValueFrom(this.update(existing.id, request));
      } else {
        await firstValueFrom(this.create(request));
      }
    } else if (existing?.id) {
      await firstValueFrom(this.remove(existing.id));
    }
  }
}
