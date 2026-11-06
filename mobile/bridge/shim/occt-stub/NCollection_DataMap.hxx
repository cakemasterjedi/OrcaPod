#pragma once
// OrcaPod adapter shim: declaration surface of OCCT's NCollection_DataMap for
// Format/STEP.hpp (Step::m_body_names). STEP import is not compiled into the
// headless engine, so the map is never populated.
#include "OCCTStubCore.hxx"

template <typename TheKeyType, typename TheItemType, typename Hasher>
class NCollection_DataMap {
public:
    Standard_Boolean IsEmpty() const { return true; }
};
