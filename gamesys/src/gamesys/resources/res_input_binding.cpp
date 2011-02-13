#include "res_input_binding.h"

#include <ddf/ddf.h>

#include <input/input.h>

#include <input/input_ddf.h>

namespace dmGameSystem
{
    dmResource::CreateResult ResInputBindingCreate(dmResource::HFactory factory,
                                     void* context,
                                     const void* buffer, uint32_t buffer_size,
                                     dmResource::SResourceDescriptor* resource,
                                     const char* filename)
    {
        dmInputDDF::InputBinding* ddf;
        dmDDF::Result e = dmDDF::LoadMessage(buffer, buffer_size, &dmInputDDF_InputBinding_DESCRIPTOR, (void**) &ddf);
        if ( e != dmDDF::RESULT_OK )
        {
            return dmResource::CREATE_RESULT_UNKNOWN;
        }
        dmInput::HBinding binding = dmInput::NewBinding((dmInput::HContext)context);
        dmInput::SetBinding(binding, ddf);
        resource->m_Resource = (void*)binding;
        dmDDF::FreeMessage((void*)ddf);

        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult ResInputBindingDestroy(dmResource::HFactory factory,
                                      void* context,
                                      dmResource::SResourceDescriptor* resource)
    {
        dmInput::DeleteBinding((dmInput::HBinding)resource->m_Resource);
        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult ResInputBindingRecreate(dmResource::HFactory factory,
            void* context,
            const void* buffer, uint32_t buffer_size,
            dmResource::SResourceDescriptor* resource,
            const char* filename)
    {
        dmInputDDF::InputBinding* ddf;
        dmDDF::Result e = dmDDF::LoadMessage(buffer, buffer_size, &dmInputDDF_InputBinding_DESCRIPTOR, (void**) &ddf);
        if ( e != dmDDF::RESULT_OK )
        {
            return dmResource::CREATE_RESULT_UNKNOWN;
        }
        dmInput::HBinding binding = (dmInput::HBinding)resource->m_Resource;
        dmInput::SetBinding(binding, ddf);
        dmDDF::FreeMessage((void*)ddf);
        return dmResource::CREATE_RESULT_OK;
    }
}
