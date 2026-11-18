file(READ "${INPUT}" SHADER_HEX HEX)
string(REGEX REPLACE "(..)" "0x\\1," SHADER_BYTES "${SHADER_HEX}")
file(WRITE "${OUTPUT}" "#pragma once\n#include <cstdint>\nalignas(4) inline constexpr unsigned char ${SYMBOL}[] = {${SHADER_BYTES}};\n")
