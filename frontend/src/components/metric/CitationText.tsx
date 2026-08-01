import { Fragment } from 'react';
import { CitationSummary } from '../../domain/agentPrototype';

export function CitationText({
  text,
  citations,
  onCitation,
}: {
  text: string;
  citations: CitationSummary[];
  onCitation: (citation: CitationSummary) => void;
}) {
  const byToken = new Map(citations.map((citation) => [citation.token, citation]));
  const parts = text.split(/(\[M[1-9][0-9]*\])/g);
  return (
    <p>
      {parts.map((part, index) => {
        const citation = byToken.get(part);
        return citation ? (
          <button
            className="citation-token"
            key={`${part}-${index}`}
            type="button"
            title={citation.label}
            onClick={() => onCitation(citation)}
          >
            {part}
          </button>
        ) : (
          <Fragment key={`${part}-${index}`}>{part}</Fragment>
        );
      })}
    </p>
  );
}
