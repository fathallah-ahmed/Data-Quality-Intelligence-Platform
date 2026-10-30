from dataclasses import dataclass, field
from typing import Dict, List, Any, Optional

@dataclass
class DatasetSummary:
    dataset_name: str
    n_rows: int
    n_cols: int
    memory_usage_bytes: int
    duplicate_rows: int
    feature_type_counts: Dict[str, int] = field(default_factory=dict)

@dataclass
class FeatureSummaryBase:
    feature_type: str
    name: str = ""
    n_total: int = 0
    non_null_count: int = 0
    missing_count: int = 0
    missing_percentage: float = 0.0
    distinct_count: int = 0
    distinct_percentage: float = 0.0
    duplicate_count: int = 0
    is_zero_relevant: bool = False
    zero_count: int = 0
    zero_percentage: float = 0.0

@dataclass
class NumericalFeatureSummary(FeatureSummaryBase):
    min: Optional[float] = None
    max: Optional[float] = None
    range: Optional[float] = None
    mean: Optional[float] = None
    median: Optional[float] = None
    q1: Optional[float] = None
    q3: Optional[float] = None
    p5: Optional[float] = None
    p95: Optional[float] = None
    iqr: Optional[float] = None
    std: Optional[float] = None
    variance: Optional[float] = None
    skewness: Optional[float] = None
    kurtosis: Optional[float] = None
    sum: Optional[float] = None
    
    most_frequent: List[Dict[str, Any]] = field(default_factory=list)
    smallest: List[Dict[str, Any]] = field(default_factory=list)
    largest: List[Dict[str, Any]] = field(default_factory=list)
    
    histogram_auto: Dict[str, Any] = field(default_factory=dict)
    histogram_5: Dict[str, Any] = field(default_factory=dict)
    histogram_15: Dict[str, Any] = field(default_factory=dict)
    histogram_30: Dict[str, Any] = field(default_factory=dict)

@dataclass
class CategoricalFeatureSummary(FeatureSummaryBase):
    top_categories: List[Dict[str, Any]] = field(default_factory=list)
    other_bucket_count: int = 0
    other_bucket_percentage: float = 0.0

@dataclass
class TextFeatureSummary(FeatureSummaryBase):
    average_length: float = 0.0
    min_length: int = 0
    max_length: int = 0
    empty_string_count: int = 0
    whitespace_only_count: int = 0
    most_frequent: List[Dict[str, Any]] = field(default_factory=list)

@dataclass
class DatetimeSuspiciousFlag:
    value: str
    count: int
    percentage: float

@dataclass
class DatetimeFeatureSummary(FeatureSummaryBase):
    min: Optional[str] = None
    max: Optional[str] = None
    most_frequent: List[Dict[str, Any]] = field(default_factory=list)
    suspicious_flags: List[DatetimeSuspiciousFlag] = field(default_factory=list)

@dataclass
class IdLikeFeatureSummary(FeatureSummaryBase):
    uniqueness_ratio: float = 0.0
    sample_values: List[Any] = field(default_factory=list)

@dataclass
class BooleanFeatureSummary(FeatureSummaryBase):
    pass

@dataclass
class UnsupportedFeatureSummary(FeatureSummaryBase):
    pass

@dataclass
class RankedAssociation:
    feature_name: str
    metric_name: str
    value: float
    direction: str

@dataclass
class FeatureAssociationsSummary:
    target_feature: str
    numerical_associations: List[RankedAssociation] = field(default_factory=list)
    categorical_associations: List[RankedAssociation] = field(default_factory=list)
    provides_information_on: List[RankedAssociation] = field(default_factory=list)
    given_information_by: List[RankedAssociation] = field(default_factory=list)

@dataclass
class AssociationPair:
    feature_1: str
    feature_2: str
    metric_name: str
    value: float
    symmetric: bool
    direction: str

@dataclass
class AssociationOutput:
    association_matrix: Dict[str, Dict[str, Optional[float]]] = field(default_factory=dict)
    association_pairs: List[AssociationPair] = field(default_factory=list)

@dataclass
class DataReport:
    dataset_summary: DatasetSummary
    feature_summaries: Dict[str, FeatureSummaryBase]
    feature_associations: Dict[str, FeatureAssociationsSummary]
    association_matrix: Dict[str, Dict[str, Optional[float]]]
    association_pairs: List[AssociationPair]
    target_feat: Optional[str] = None
