package com.cocos.challenge.instruments.infrastructure.persistence;

import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.instruments.domain.InstrumentType;
import com.cocos.challenge.shared.infrastructure.persistence.PersistedDataIntegrityException;

final class InstrumentMapper {

    private InstrumentMapper() {
    }

    static Instrument toDomain(InstrumentEntity entity) {
        return new Instrument(entity.getId(), entity.getTicker(), entity.getName(), type(entity));
    }

    private static InstrumentType type(InstrumentEntity entity) {
        String raw = entity.getType();
        if (raw != null) {
            for (InstrumentType candidate : InstrumentType.values()) {
                if (candidate.name().equals(raw)) {
                    return candidate;
                }
            }
        }
        throw new PersistedDataIntegrityException(
                "instruments.id=" + entity.getId() + " has an unsupported type: " + raw);
    }
}
