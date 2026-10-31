import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { VersionDetails } from '../../core/models';
import { EmptyState, InfoTip } from '../../shared/components';
import { Icon } from '../../shared/icon';
import { DQ_PIPES } from '../../shared/pipes';
import { DataModelMapComponent } from './data-model-map';
import { buildDataModelMap, buildEntities, buildRelations, dataModelSvg, modelTables } from './data-model';

@Component({
  selector: 'app-data-model-tab',
  imports: [DataModelMapComponent, EmptyState, InfoTip, Icon, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './data-model-tab.html',
  styleUrl: './data-model-tab.css'
})
export class DataModelTab {
  readonly details = input.required<VersionDetails>();
  readonly datasetName = input('dataset');

  readonly tables = computed(() => modelTables(this.details().tables));
  readonly relations = computed(() => buildRelations(this.tables()));
  readonly entities = computed(() => buildEntities(this.tables(), this.relations()));
  readonly model = computed(() => buildDataModelMap(this.tables(), this.entities(), this.relations()));
  readonly inferredOnly = computed(() => this.relations().length > 0 && this.relations().every((relation) => relation.origin === 'names'));

  download(): void {
    const name = this.datasetName().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'dataset';
    const blob = new Blob([dataModelSvg(this.model())], { type: 'image/svg+xml;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${name}-v${this.details().version.versionNumber}-data-model.svg`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
