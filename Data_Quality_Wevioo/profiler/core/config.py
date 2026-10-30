from dataclasses import dataclass

@dataclass
class TypeInferenceConfig:
    # Thresholds for type inference heuristics
    max_categorical_unique: int = 15      # Max unique values for a numerical column to be forced to categorical
    max_categorical_ratio: float = 0.5    # Max unique ratio for object to be categorical rather than text
    id_like_unique_ratio: float = 0.95    # Ratio of uniqueness above which a column is ID-like
    datetime_parsing: bool = True         # Allow attempting to parse strings actively as datetimes if requested
    top_categories_k: int = 15            # Number of categories to grab for Top Categories summaries
    top_associations_k: int = 15          # Max associations returned per feature

config = TypeInferenceConfig()
